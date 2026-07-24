import base64
import asyncio
import json
import logging
import os
import re
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated, Any
from urllib.parse import urlparse

import numpy as np
import requests
from fastapi import FastAPI, File, Form, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from backend.app.database import check_database_ready, init_database, using_runtime_sqlite_fallback
from backend.app.routers import ai, exercises, progress, screening, simplify, users
from backend.app.services.adaptation_service import build_adaptation_payload
from backend.app.services.syllabification import clean_ocr_text as service_clean_ocr_text

try:
    import cv2
except Exception as exc:
    cv2 = None
    CV2_IMPORT_ERROR: Exception | None = exc
else:
    CV2_IMPORT_ERROR = None

try:
    from bs4 import BeautifulSoup
except Exception as exc:
    BeautifulSoup = None
    BEAUTIFULSOUP_IMPORT_ERROR: Exception | None = exc
else:
    BEAUTIFULSOUP_IMPORT_ERROR = None

try:
    import easyocr
except Exception as exc:
    easyocr = None
    EASYOCR_IMPORT_ERROR: Exception | None = exc
else:
    EASYOCR_IMPORT_ERROR = None

try:
    from newspaper import Article, Config
except Exception as exc:
    Article = None
    Config = None
    NEWSPAPER_IMPORT_ERROR: Exception | None = exc
else:
    NEWSPAPER_IMPORT_ERROR = None


logging.basicConfig(
    level=os.getenv("SULU_READ_LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("sulu_read_backend")

URL_ERROR_MESSAGE = "Не удалось прочитать ссылку. Попробуйте скопировать текст вручную."
IMAGE_ERROR_MESSAGE = "Текст на фото слишком размытый. Пожалуйста, сделайте снимок при хорошем освещении."
GENERIC_ERROR_MESSAGE = "Не удалось обработать запрос. Пожалуйста, попробуйте еще раз."

MAX_IMAGE_BYTES = int(os.getenv("SULU_READ_MAX_IMAGE_BYTES", str(15 * 1024 * 1024)))
MAX_TEXT_CHARS = int(os.getenv("SULU_READ_MAX_TEXT_CHARS", "50000"))
URL_EXTRACTION_TIMEOUT_SECONDS = float(os.getenv("SULU_READ_URL_TIMEOUT_SECONDS", "20"))
OCR_TIMEOUT_SECONDS = float(os.getenv("SULU_READ_OCR_TIMEOUT_SECONDS", "120"))
OCR_MIN_CYRILLIC_CHARS = int(os.getenv("SULU_READ_OCR_MIN_CYRILLIC_CHARS", "20"))
OCR_MAX_IMAGE_SIDE = int(os.getenv("SULU_READ_OCR_MAX_IMAGE_SIDE", "2600"))
GROQ_TIMEOUT_SECONDS = float(os.getenv("SULU_READ_GROQ_TIMEOUT_SECONDS", "90"))
GROQ_VISION_MODEL = os.getenv(
    "SULU_READ_GROQ_VISION_MODEL",
    "meta-llama/llama-4-maverick-17b-128e-instruct",
)
GROQ_VISION_FALLBACK_MODEL = os.getenv(
    "SULU_READ_GROQ_VISION_FALLBACK_MODEL",
    "meta-llama/llama-4-scout-17b-16e-instruct",
)
GROQ_MAX_IMAGE_SIDE = int(os.getenv("SULU_READ_GROQ_MAX_IMAGE_SIDE", "1800"))
GROQ_MAX_IMAGE_BYTES = int(os.getenv("SULU_READ_GROQ_MAX_IMAGE_BYTES", str(2_800_000)))

RUSSIAN_VOWELS = set("аеёиоуыэюяАЕЁИОУЫЭЮЯ")
KAZAKH_VOWELS = set("аәеёиоөұүыіуАӘЕЁИОӨҰҮЫІУ")
ALL_CYRILLIC_VOWELS = RUSSIAN_VOWELS | KAZAKH_VOWELS
KAZAKH_FRONT_VOWELS = set("әеөүіӘЕӨҮІ")
KAZAKH_BACK_VOWELS = set("аоұыАОҰЫ")
KAZAKH_SPECIFIC_LETTERS = set("әғқңөұүһіӘҒҚҢӨҰҮҺІ")
CYRILLIC_LETTERS = set(
    "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    "әғқңөұүһіӘҒҚҢӨҰҮҺІ"
)
LATIN_LETTERS = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")

LETTER_CLASS = "A-Za-zА-Яа-яЁёӘәҒғҚқҢңӨөҰұҮүҺһІі"
STANDARD_SYLLABLE_DELIMITER = "-"
SECONDARY_SYLLABLE_DIVIDERS = "·•∙⋅●"
OCR_TEXT_REPLACEMENTS = (
    ("$", "§"),
    ("＄", "§"),
    ("﹩", "§"),
)
KNOWN_PROPER_NOUN_ROOTS = (
    "қазақстан",
    "казахстан",
)
HYPHENATED_WORD_PATTERN = re.compile(
    rf"[{LETTER_CLASS}]+(?:-[{LETTER_CLASS}]+)+",
    re.UNICODE,
)

TOKEN_PATTERN = re.compile(
    rf"[{LETTER_CLASS}]+(?:['’][{LETTER_CLASS}]+)?"
    r"|[0-9]+"
    r"|[^\w\s]+"
    r"|\s+",
    re.UNICODE,
)

SUPPORTED_IMAGE_EXTENSIONS = {
    ".bmp",
    ".gif",
    ".heic",
    ".heif",
    ".jpeg",
    ".jpg",
    ".png",
    ".tif",
    ".tiff",
    ".webp",
}


class UrlRequest(BaseModel):
    url: str = Field(min_length=1, max_length=4096)
    language_hint: str = Field(default="kk", max_length=20)


class AdaptedWord(BaseModel):
    original: str
    adapted: str
    syllables: list[str]
    language_hint: str
    vowel_harmony: str | None = None


class AdaptedTextResponse(BaseModel):
    status: str
    source: str
    original_text: str
    adapted_text: str
    word_count: int
    unique_word_count: int
    truncated: bool
    words: list[AdaptedWord]
    title: str | None = None


class ErrorResponse(BaseModel):
    status: str
    message: str


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.db_ready = init_database()
    app.state.ocr_reader = None
    app.state.ocr_languages = []
    app.state.ocr_lock = asyncio.Lock()
    app.state.ocr_status = "loading"
    app.state.ocr_error = None

    if easyocr is None:
        logger.error("EasyOCR import failed: %s", EASYOCR_IMPORT_ERROR)
        app.state.ocr_status = "error"
        app.state.ocr_error = str(EASYOCR_IMPORT_ERROR)
        yield
        return

    ocr_task = asyncio.create_task(initialize_ocr_reader(app))
    try:
        yield
    finally:
        if not ocr_task.done():
            ocr_task.cancel()
            try:
                await ocr_task
            except asyncio.CancelledError:
                logger.info("EasyOCR initialization task cancelled")


async def initialize_ocr_reader(app: FastAPI) -> None:
    preferred_languages = get_easyocr_languages()
    try:
        app.state.ocr_reader = await asyncio.to_thread(
            easyocr.Reader,
            preferred_languages,
            gpu=should_use_gpu(),
        )
        app.state.ocr_languages = preferred_languages
        app.state.ocr_status = "ready"
        app.state.ocr_error = None
        logger.info("EasyOCR initialized with languages: %s", preferred_languages)
    except Exception:
        logger.exception("EasyOCR initialization failed for languages: %s", preferred_languages)
        fallback_languages = ["ru", "en"]
        if preferred_languages != fallback_languages:
            try:
                app.state.ocr_reader = await asyncio.to_thread(
                    easyocr.Reader,
                    fallback_languages,
                    gpu=should_use_gpu(),
                )
                app.state.ocr_languages = fallback_languages
                app.state.ocr_status = "ready"
                app.state.ocr_error = None
                logger.info("EasyOCR initialized with fallback languages: %s", fallback_languages)
                return
            except Exception:
                logger.exception("EasyOCR fallback initialization failed")
        app.state.ocr_status = "error"
        app.state.ocr_error = "EasyOCR initialization failed"


app = FastAPI(
    title="Sulu-Read API",
    version="1.0.0",
    description="FastAPI backend for dyslexia-friendly text adaptation from URLs and textbook images.",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
    allow_credentials=False,
)

app.include_router(users.router)
app.include_router(exercises.router)
app.include_router(screening.router)
app.include_router(progress.router)
app.include_router(simplify.router)
app.include_router(ai.router)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    logger.warning("Validation failed for %s: %s", request.url.path, exc)
    if request.url.path == "/v1/adapt-url":
        return error_response(URL_ERROR_MESSAGE)
    if request.url.path == "/v1/adapt-image":
        return error_response(IMAGE_ERROR_MESSAGE)
    if request.url.path == "/ai/generate":
        return JSONResponse(
            status_code=200,
            content={
                "success": False,
                "error": "AI help is temporarily unavailable. Please try again later.",
            },
        )
    return error_response(GENERIC_ERROR_MESSAGE)


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    logger.error(
        "Unhandled error for %s",
        request.url.path,
        exc_info=(type(exc), exc, exc.__traceback__),
    )
    return error_response(GENERIC_ERROR_MESSAGE, status_code=500)


@app.get("/")
async def root() -> dict[str, Any]:
    return {
        "status": "ok",
        "service": "Sulu-Read API",
        "health": "/health",
        "docs": "/docs",
        "endpoints": {
            "adapt_url": "/v1/adapt-url",
            "adapt_image": "/v1/adapt-image",
            "register": "/v1/users/register",
            "login": "/v1/users/login",
            "ai_generate": "/ai/generate",
        },
    }


@app.get("/health")
async def health(request: Request) -> dict[str, Any]:
    request.app.state.db_ready = check_database_ready()
    return {
        "status": "ok",
        "db_ready": request.app.state.db_ready,
        "db_runtime_sqlite_fallback": using_runtime_sqlite_fallback(),
        "training_ready": True,
        "ocr_ready": request.app.state.ocr_reader is not None,
        "ocr_status": request.app.state.ocr_status,
        "ocr_languages": request.app.state.ocr_languages,
        "ocr_error": request.app.state.ocr_error,
        "groq_vision_ready": has_groq_vision_key(),
        "groq_vision_models": (
            get_groq_vision_models(get_groq_api_key())[:4] if has_groq_vision_key() else []
        ),
    }


@app.post("/v1/adapt-url", response_model=AdaptedTextResponse | ErrorResponse)
async def adapt_url(payload: UrlRequest) -> JSONResponse | AdaptedTextResponse:
    try:
        url = payload.url.strip()
        if not is_valid_http_url(url):
            return error_response(URL_ERROR_MESSAGE)

        title, extracted_text = await asyncio.wait_for(
            asyncio.to_thread(extract_text_from_url, url),
            timeout=URL_EXTRACTION_TIMEOUT_SECONDS,
        )
        if not extracted_text:
            return error_response(URL_ERROR_MESSAGE)

        return build_adapted_response(
            source="url",
            text=extracted_text,
            title=title,
        )
    except Exception:
        logger.exception("URL adaptation failed")
        return error_response(URL_ERROR_MESSAGE)


@app.post("/v1/adapt-image", response_model=AdaptedTextResponse | ErrorResponse)
async def adapt_image(
    request: Request,
    file: Annotated[UploadFile, File(description="Textbook page image")],
    language_hint: Annotated[str, Form()] = "kk",
) -> JSONResponse | AdaptedTextResponse:
    temp_path: str | None = None
    try:
        if not is_supported_image(file):
            return error_response(IMAGE_ERROR_MESSAGE)

        image_bytes = await file.read(MAX_IMAGE_BYTES + 1)
        if not image_bytes or len(image_bytes) > MAX_IMAGE_BYTES:
            return error_response(IMAGE_ERROR_MESSAGE)

        extracted_text = ""
        temp_path = write_temp_image(image_bytes, file.filename)

        if has_groq_vision_key():
            extracted_text = await asyncio.wait_for(
                asyncio.to_thread(
                    read_text_with_groq_vision,
                    image_bytes,
                    file.filename,
                    language_hint,
                ),
                timeout=GROQ_TIMEOUT_SECONDS,
            )

        if not extracted_text and request.app.state.ocr_reader is not None:
            logger.info("Groq Vision did not extract enough text; using EasyOCR fallback")
            async with request.app.state.ocr_lock:
                extracted_text = await asyncio.wait_for(
                    asyncio.to_thread(
                        read_text_from_image,
                        request.app.state.ocr_reader,
                        temp_path,
                    ),
                    timeout=OCR_TIMEOUT_SECONDS,
                )

        extracted_text = service_clean_ocr_text(extracted_text)
        if not extracted_text:
            return error_response(IMAGE_ERROR_MESSAGE)

        return build_adapted_response(
            source="image",
            text=extracted_text,
            title=file.filename,
        )
    except Exception:
        logger.exception("Image adaptation failed")
        return error_response(IMAGE_ERROR_MESSAGE)
    finally:
        await close_upload_file(file)
        if temp_path:
            remove_temp_file(temp_path)


def error_response(message: str, status_code: int = 200) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={
            "status": "error",
            "message": message,
        },
    )


def should_use_gpu() -> bool:
    return os.getenv("SULU_READ_EASYOCR_GPU", "false").strip().lower() in {
        "1",
        "true",
        "yes",
        "y",
        "on",
    }


def get_easyocr_languages() -> list[str]:
    # "mn" shares the Cyrillic model and adds ө/ү needed for Kazakh; Serbian
    # ("rs_cyrillic") is excluded because it injects ђјљњћџ into ru/kk text.
    languages = ["ru", "mn", "en"]
    try:
        from easyocr.config import all_lang_list
    except Exception:
        return languages

    if "kk" in all_lang_list:
        return ["ru", "kk", "en"]
    filtered = [language for language in languages if language in all_lang_list]
    return filtered or ["ru", "en"]


def is_valid_http_url(url: str) -> bool:
    parsed = urlparse(url)
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


def extract_text_from_url(url: str) -> tuple[str | None, str]:
    if Article is not None:
        try:
            config = None
            if Config is not None:
                config = Config()
                config.browser_user_agent = browser_user_agent()
                config.request_timeout = int(URL_EXTRACTION_TIMEOUT_SECONDS)
                config.fetch_images = False

            article = Article(url, config=config) if config is not None else Article(url)
            article.download()
            article.parse()

            title = normalize_text(getattr(article, "title", "") or "")
            body = normalize_text(getattr(article, "text", "") or "")
            if body:
                return title or None, body
        except Exception:
            logger.info("newspaper3k extraction failed, falling back to HTML extraction", exc_info=True)
    elif NEWSPAPER_IMPORT_ERROR is not None:
        logger.info("newspaper3k is unavailable, falling back to HTML extraction: %s", NEWSPAPER_IMPORT_ERROR)

    return extract_text_from_html(url)


def browser_user_agent() -> str:
    return (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0 Safari/537.36 SuluRead/1.0"
    )


def extract_text_from_html(url: str) -> tuple[str | None, str]:
    if BeautifulSoup is None:
        raise RuntimeError("BeautifulSoup is unavailable") from BEAUTIFULSOUP_IMPORT_ERROR

    response = requests.get(
        url,
        timeout=URL_EXTRACTION_TIMEOUT_SECONDS,
        headers={"User-Agent": browser_user_agent()},
    )
    response.raise_for_status()

    soup = BeautifulSoup(response.text, "html.parser")
    for tag in soup(["script", "style", "noscript", "svg", "nav", "footer", "header"]):
        tag.decompose()

    title = normalize_text(soup.title.get_text(" ", strip=True)) if soup.title else None
    candidates = soup.find_all(["article", "main"])
    if not candidates:
        body = soup.body if soup.body is not None else soup
        candidates = [body]

    text_blocks: list[str] = []
    for candidate in candidates:
        for element in candidate.find_all(["h1", "h2", "h3", "p", "li", "blockquote"]):
            text = normalize_text(element.get_text(" ", strip=True))
            if len(text) >= 2:
                text_blocks.append(text)

    body_text = normalize_text("\n".join(text_blocks))
    if not body_text:
        body_text = normalize_text(" ".join(candidate.get_text(" ", strip=True) for candidate in candidates))

    if not body_text:
        raise ValueError("HTML text is empty")

    return title, body_text


def is_supported_image(file: UploadFile) -> bool:
    content_type = (file.content_type or "").lower()
    if content_type.startswith("image/"):
        return True

    suffix = Path(file.filename or "").suffix.lower()
    return suffix in SUPPORTED_IMAGE_EXTENSIONS


def write_temp_image(image_bytes: bytes, filename: str | None) -> str:
    suffix = Path(filename or "").suffix.lower()
    if suffix not in SUPPORTED_IMAGE_EXTENSIONS:
        suffix = ".jpg"

    temp_file = tempfile.NamedTemporaryFile(
        mode="wb",
        suffix=suffix,
        prefix="sulu_read_ocr_",
        delete=False,
    )
    try:
        temp_file.write(image_bytes)
        return temp_file.name
    finally:
        temp_file.close()


def read_text_from_image(reader: Any, image_path: str) -> str:
    candidate_paths = create_ocr_candidate_images(image_path)
    best_text = ""
    best_score = -1.0

    try:
        for candidate_path in candidate_paths:
            try:
                ocr_result = reader.readtext(
                    candidate_path,
                    detail=1,
                    paragraph=False,
                    decoder="beamsearch",
                    batch_size=8,
                    rotation_info=[0],
                    canvas_size=2560,
                    mag_ratio=1.5,
                    contrast_ths=0.03,
                    adjust_contrast=0.8,
                    text_threshold=0.45,
                    low_text=0.25,
                    link_threshold=0.25,
                )
            except TypeError:
                ocr_result = reader.readtext(candidate_path, detail=1, paragraph=False)
            except Exception:
                logger.info("OCR pass failed for %s", candidate_path, exc_info=True)
                continue

            text = normalize_text(extract_text_from_ocr_result(ocr_result))
            score = score_ocr_text(text, ocr_result)
            if score > best_score:
                best_score = score
                best_text = text

            if count_readable_letters(best_text) >= OCR_MIN_CYRILLIC_CHARS and len(best_text) >= 80:
                break
    finally:
        for candidate_path in candidate_paths:
            if candidate_path != image_path:
                remove_temp_file(candidate_path)

    return best_text if count_readable_letters(best_text) >= OCR_MIN_CYRILLIC_CHARS else ""


def create_ocr_candidate_images(image_path: str) -> list[str]:
    if cv2 is None:
        logger.warning("OpenCV is unavailable for OCR preprocessing: %s", CV2_IMPORT_ERROR)
        return [image_path]

    image = cv2.imread(image_path)
    if image is None:
        return [image_path]

    image = resize_for_ocr(image)
    page_crop = crop_likely_page(image)
    grayscale = cv2.cvtColor(page_crop, cv2.COLOR_BGR2GRAY)
    denoised = cv2.fastNlMeansDenoising(grayscale, None, 12, 7, 21)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(denoised)
    sharpened = sharpen_image(clahe)
    adaptive = cv2.adaptiveThreshold(
        sharpened,
        255,
        cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY,
        31,
        11,
    )
    otsu = cv2.threshold(sharpened, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[1]

    candidates = [
        page_crop,
        cv2.cvtColor(sharpened, cv2.COLOR_GRAY2BGR),
        cv2.cvtColor(adaptive, cv2.COLOR_GRAY2BGR),
        cv2.cvtColor(otsu, cv2.COLOR_GRAY2BGR),
    ]

    candidate_paths = [image_path]
    for index, candidate in enumerate(candidates):
        path = tempfile.NamedTemporaryFile(
            mode="wb",
            suffix=f"_ocr_candidate_{index}.png",
            prefix="sulu_read_",
            delete=False,
        ).name
        cv2.imwrite(path, candidate)
        candidate_paths.append(path)

    return candidate_paths


def resize_for_ocr(image: np.ndarray) -> np.ndarray:
    height, width = image.shape[:2]
    longest_side = max(height, width)
    if longest_side <= OCR_MAX_IMAGE_SIDE:
        return image

    scale = OCR_MAX_IMAGE_SIDE / longest_side
    new_size = (max(1, int(width * scale)), max(1, int(height * scale)))
    return cv2.resize(image, new_size, interpolation=cv2.INTER_AREA)


def crop_likely_page(image: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blurred, 40, 120)
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    if not contours:
        return trim_border(image)

    image_area = image.shape[0] * image.shape[1]
    contours = sorted(contours, key=cv2.contourArea, reverse=True)
    for contour in contours[:8]:
        area = cv2.contourArea(contour)
        if area < image_area * 0.25:
            continue

        perimeter = cv2.arcLength(contour, True)
        approximation = cv2.approxPolyDP(contour, 0.02 * perimeter, True)
        if len(approximation) == 4:
            warped = four_point_transform(image, approximation.reshape(4, 2))
            if warped.shape[0] > 200 and warped.shape[1] > 200:
                return trim_border(warped)

    return trim_border(image)


def trim_border(image: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    mask = gray < 245
    coordinates = cv2.findNonZero(mask.astype(np.uint8))
    if coordinates is None:
        return image

    x, y, width, height = cv2.boundingRect(coordinates)
    padding = 12
    x1 = max(0, x - padding)
    y1 = max(0, y - padding)
    x2 = min(image.shape[1], x + width + padding)
    y2 = min(image.shape[0], y + height + padding)
    return image[y1:y2, x1:x2]


def four_point_transform(image: np.ndarray, points: np.ndarray) -> np.ndarray:
    rect = order_points(points.astype("float32"))
    top_left, top_right, bottom_right, bottom_left = rect

    width_a = np.linalg.norm(bottom_right - bottom_left)
    width_b = np.linalg.norm(top_right - top_left)
    max_width = max(1, int(max(width_a, width_b)))

    height_a = np.linalg.norm(top_right - bottom_right)
    height_b = np.linalg.norm(top_left - bottom_left)
    max_height = max(1, int(max(height_a, height_b)))

    destination = np.array(
        [
            [0, 0],
            [max_width - 1, 0],
            [max_width - 1, max_height - 1],
            [0, max_height - 1],
        ],
        dtype="float32",
    )

    matrix = cv2.getPerspectiveTransform(rect, destination)
    return cv2.warpPerspective(image, matrix, (max_width, max_height))


def order_points(points: np.ndarray) -> np.ndarray:
    rect = np.zeros((4, 2), dtype="float32")
    point_sum = points.sum(axis=1)
    rect[0] = points[np.argmin(point_sum)]
    rect[2] = points[np.argmax(point_sum)]

    point_diff = np.diff(points, axis=1)
    rect[1] = points[np.argmin(point_diff)]
    rect[3] = points[np.argmax(point_diff)]
    return rect


def sharpen_image(gray: np.ndarray) -> np.ndarray:
    blurred = cv2.GaussianBlur(gray, (0, 0), sigmaX=1.2)
    return cv2.addWeighted(gray, 1.6, blurred, -0.6, 0)


def extract_text_from_ocr_result(ocr_result: list[Any]) -> str:
    lines: list[tuple[float, float, str]] = []
    fallback_parts: list[str] = []

    for item in ocr_result:
        if isinstance(item, str):
            fallback_parts.append(item)
            continue

        if isinstance(item, (list, tuple)) and len(item) >= 2:
            text = str(item[1]).strip()
            if not text:
                continue

            box = item[0]
            if isinstance(box, (list, tuple)) and box:
                xs = [point[0] for point in box if isinstance(point, (list, tuple)) and len(point) >= 2]
                ys = [point[1] for point in box if isinstance(point, (list, tuple)) and len(point) >= 2]
                if xs and ys:
                    lines.append((float(sum(ys) / len(ys)), float(min(xs)), text))
                    continue

            fallback_parts.append(text)

    if not lines:
        return "\n".join(fallback_parts)

    lines.sort(key=lambda row: (row[0], row[1]))
    grouped_lines: list[list[tuple[float, float, str]]] = []
    for line in lines:
        if not grouped_lines or abs(grouped_lines[-1][0][0] - line[0]) > 18:
            grouped_lines.append([line])
        else:
            grouped_lines[-1].append(line)

    text_lines: list[str] = []
    for group in grouped_lines:
        group.sort(key=lambda row: row[1])
        text_lines.append(" ".join(row[2] for row in group))

    return "\n".join(text_lines)


def score_ocr_text(text: str, ocr_result: list[Any]) -> float:
    readable_count = count_readable_letters(text)
    digit_count = sum(1 for character in text if character.isdigit())
    confidence_values = [
        float(item[2])
        for item in ocr_result
        if isinstance(item, (list, tuple))
        and len(item) >= 3
        and isinstance(item[2], (int, float))
    ]
    average_confidence = sum(confidence_values) / len(confidence_values) if confidence_values else 0.0
    return readable_count * 3.0 + digit_count * 0.5 + average_confidence * 30.0


def count_cyrillic_letters(text: str) -> int:
    return sum(1 for character in text if character in CYRILLIC_LETTERS)


def count_latin_letters(text: str) -> int:
    return sum(1 for character in text if character in LATIN_LETTERS)


def count_readable_letters(text: str) -> int:
    return count_cyrillic_letters(text) + count_latin_letters(text)


def has_groq_vision_key() -> bool:
    return bool(get_groq_api_key())


GROQ_MODELS_CACHE: dict[str, list[str] | None] = {"models": None}


def list_groq_models(api_key: str) -> list[str]:
    cached = GROQ_MODELS_CACHE["models"]
    if cached is not None:
        return cached

    try:
        response = requests.get(
            "https://api.groq.com/openai/v1/models",
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=20,
        )
        response.raise_for_status()
        model_ids = [str(item.get("id", "")) for item in response.json().get("data", [])]
        GROQ_MODELS_CACHE["models"] = model_ids
        logger.info("Groq models available: %s", model_ids)
        return model_ids
    except Exception:
        logger.exception("Groq models listing failed")
        return []


def get_groq_vision_models(api_key: str) -> list[str]:
    preferred = [model for model in dict.fromkeys([GROQ_VISION_MODEL, GROQ_VISION_FALLBACK_MODEL]) if model]
    available = list_groq_models(api_key)
    if not available:
        return preferred

    models = [model for model in preferred if model in available]
    vision_markers = ("llama-4", "maverick", "scout", "vision", "llava", "-vl-", "vl-")
    for model_id in available:
        lowered = model_id.lower()
        if model_id in models or "guard" in lowered or "whisper" in lowered or "tts" in lowered:
            continue
        if any(marker in lowered for marker in vision_markers):
            models.append(model_id)

    return models or preferred


def get_groq_api_key() -> str:
    return (os.getenv("GROQ_API") or os.getenv("GROQ_API_KEY") or "").strip()


GROQ_LANGUAGE_HINT_NAMES = {
    "kk": "Kazakh",
    "ru": "Russian",
    "en": "English",
}


def build_groq_ocr_prompt(language_hint: str) -> str:
    hint_name = GROQ_LANGUAGE_HINT_NAMES.get((language_hint or "").strip().lower())
    hint_sentence = (
        f"The text is most likely in {hint_name}, but other languages may also appear. "
        if hint_name
        else ""
    )
    return (
        "You are a precise OCR engine for photos of school textbook pages in Kazakh, "
        "Russian, and English. "
        + hint_sentence +
        "Extract ONLY the text that is actually printed in the image, exactly as written. "
        "Kazakh uses these extra Cyrillic letters: Әә Ғғ Ққ Ңң Өө Ұұ Үү Һһ Іі — reproduce "
        "them exactly and NEVER replace them with similar Russian letters (қ is not к, "
        "ә is not а, ө is not о, ұ/ү is not у, і is not и, ғ is not г, ң is not н). "
        "Never swap Latin letters for Cyrillic lookalikes or vice versa. "
        "Keep the original letter case, punctuation, and line breaks where useful. "
        "Do not summarize, translate, explain, answer questions, or invent missing words. "
        "Ignore page shadows, fingers, table edges, UI elements, and neighboring pages. "
        "Return JSON with exactly one key: {\"text\":\"...\"}. "
        "If no printed text is readable, return {\"text\":\"\"}."
    )


def read_text_with_groq_vision(
    image_bytes: bytes,
    filename: str | None = None,
    language_hint: str = "kk",
) -> str:
    api_key = get_groq_api_key()
    if not api_key:
        return ""

    try:
        image_payload = prepare_image_for_groq(image_bytes)
        image_base64 = base64.b64encode(image_payload).decode("ascii")
    except Exception:
        logger.exception("Groq Vision image preparation failed")
        return ""

    models_to_try = get_groq_vision_models(api_key)[:4]
    prompt = build_groq_ocr_prompt(language_hint)
    for model in models_to_try:
        try:
            response = requests.post(
                "https://api.groq.com/openai/v1/chat/completions",
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": model,
                    "temperature": 0,
                    "max_completion_tokens": 4096,
                    "response_format": {"type": "json_object"},
                    "messages": [
                        {
                            "role": "user",
                            "content": [
                                {"type": "text", "text": prompt},
                                {
                                    "type": "image_url",
                                    "image_url": {
                                        "url": f"data:image/jpeg;base64,{image_base64}",
                                    },
                                },
                            ],
                        }
                    ],
                },
                timeout=GROQ_TIMEOUT_SECONDS,
            )

            if response.status_code >= 400:
                logger.warning(
                    "Groq Vision OCR (%s) failed with status %s: %s",
                    model,
                    response.status_code,
                    response.text[:500],
                )
                continue

            payload = response.json()
            content = (
                payload.get("choices", [{}])[0]
                .get("message", {})
                .get("content", "")
            )
            text = extract_groq_text_content(content)
            if count_readable_letters(text) < OCR_MIN_CYRILLIC_CHARS:
                logger.info(
                    "Groq Vision OCR (%s) returned too little text (%s readable letters)",
                    model,
                    count_readable_letters(text),
                )
                continue

            logger.info(
                "Groq Vision OCR (%s) extracted %s characters from %s",
                model,
                len(text),
                filename or "uploaded image",
            )
            return text
        except Exception:
            logger.exception("Groq Vision OCR failed for model %s", model)

    return ""


def prepare_image_for_groq(image_bytes: bytes) -> bytes:
    if cv2 is None:
        return image_bytes if len(image_bytes) <= GROQ_MAX_IMAGE_BYTES else image_bytes[:GROQ_MAX_IMAGE_BYTES]

    image_array = np.frombuffer(image_bytes, dtype=np.uint8)
    image = cv2.imdecode(image_array, cv2.IMREAD_COLOR)
    if image is None:
        return image_bytes if len(image_bytes) <= GROQ_MAX_IMAGE_BYTES else image_bytes[:GROQ_MAX_IMAGE_BYTES]

    image = crop_likely_page(image)
    image = resize_to_max_side(image, GROQ_MAX_IMAGE_SIDE)

    for quality in (90, 84, 78, 72, 66):
        success, encoded = cv2.imencode(
            ".jpg",
            image,
            [int(cv2.IMWRITE_JPEG_QUALITY), quality],
        )
        if success and encoded.nbytes <= GROQ_MAX_IMAGE_BYTES:
            return encoded.tobytes()

    current = image
    while max(current.shape[:2]) > 900:
        current = resize_to_max_side(current, int(max(current.shape[:2]) * 0.85))
        success, encoded = cv2.imencode(
            ".jpg",
            current,
            [int(cv2.IMWRITE_JPEG_QUALITY), 68],
        )
        if success and encoded.nbytes <= GROQ_MAX_IMAGE_BYTES:
            return encoded.tobytes()

    success, encoded = cv2.imencode(".jpg", current, [int(cv2.IMWRITE_JPEG_QUALITY), 60])
    if success:
        return encoded.tobytes()
    return image_bytes


def resize_to_max_side(image: np.ndarray, max_side: int) -> np.ndarray:
    height, width = image.shape[:2]
    longest_side = max(height, width)
    if longest_side <= max_side:
        return image

    scale = max_side / longest_side
    new_size = (max(1, int(width * scale)), max(1, int(height * scale)))
    return cv2.resize(image, new_size, interpolation=cv2.INTER_AREA)


def extract_groq_text_content(content: str) -> str:
    cleaned = content.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?", "", cleaned, flags=re.IGNORECASE).strip()
        cleaned = re.sub(r"```$", "", cleaned).strip()

    try:
        parsed = json.loads(cleaned)
        if isinstance(parsed, dict):
            return normalize_text(str(parsed.get("text", "")))
    except json.JSONDecodeError:
        pass

    return normalize_text(cleaned)


async def close_upload_file(file: UploadFile) -> None:
    try:
        await file.close()
    except Exception:
        logger.debug("UploadFile close failed", exc_info=True)


def remove_temp_file(path: str) -> None:
    try:
        os.remove(path)
    except FileNotFoundError:
        return
    except Exception:
        logger.warning("Failed to remove temporary file: %s", path, exc_info=True)


def build_adapted_response(source: str, text: str, title: str | None = None) -> AdaptedTextResponse:
    payload = build_adaptation_payload(
        source=source,
        text=text,
        title=title,
        max_text_chars=MAX_TEXT_CHARS,
    )

    return AdaptedTextResponse(
        status=payload["status"],
        source=payload["source"],
        title=payload["title"],
        original_text=payload["original_text"],
        adapted_text=payload["adapted_text"],
        word_count=payload["word_count"],
        unique_word_count=payload["unique_word_count"],
        truncated=payload["truncated"],
        words=[AdaptedWord(**word) for word in payload["words"]],
    )


def prepare_text_for_adaptation(source: str, text: str) -> str:
    prepared_text = normalize_text(text)
    if source == "image":
        prepared_text = clean_ocr_text(prepared_text)
        prepared_text = sentence_case_text(prepared_text.lower())

    prepared_text = remove_existing_syllable_markup(prepared_text)
    if source == "image":
        prepared_text = restore_known_proper_nouns(prepared_text)

    return normalize_text(prepared_text)


def clean_ocr_text(raw_ocr_text: str) -> str:
    cleaned_text = raw_ocr_text
    for old_value, new_value in OCR_TEXT_REPLACEMENTS:
        cleaned_text = cleaned_text.replace(old_value, new_value)

    cleaned_text = re.sub(r"§\s+(\d)", r"§\1", cleaned_text)
    cleaned_text = re.sub(r"(?<=\d)\s+([.,:;!?])", r"\1", cleaned_text)
    return normalize_text(cleaned_text)


def sentence_case_text(text: str) -> str:
    cased_characters: list[str] = []
    capitalize_next_letter = True

    for character in text:
        if character.isalpha():
            if capitalize_next_letter:
                cased_characters.append(character.upper())
                capitalize_next_letter = False
            else:
                cased_characters.append(character)
            continue

        cased_characters.append(character)
        if character in ".!?…\n":
            capitalize_next_letter = True

    return restore_known_proper_nouns("".join(cased_characters))


def restore_known_proper_nouns(text: str) -> str:
    word_pattern = re.compile(rf"[{LETTER_CLASS}]+", re.UNICODE)

    def restore_word(match: re.Match[str]) -> str:
        word = match.group(0)
        lowered_word = word.lower()
        if any(lowered_word.startswith(root) for root in KNOWN_PROPER_NOUN_ROOTS):
            return word[:1].upper() + word[1:]
        return word

    return word_pattern.sub(restore_word, text)


def remove_existing_syllable_markup(text: str) -> str:
    cleaned_text = text
    for divider in SECONDARY_SYLLABLE_DIVIDERS:
        cleaned_text = cleaned_text.replace(divider, STANDARD_SYLLABLE_DELIMITER)

    cleaned_text = re.sub(
        rf"(?<=[{LETTER_CLASS}])\s*-\s*(?=[{LETTER_CLASS}])",
        STANDARD_SYLLABLE_DELIMITER,
        cleaned_text,
    )
    cleaned_text = re.sub(r"-{2,}", STANDARD_SYLLABLE_DELIMITER, cleaned_text)

    def remove_syllable_hyphens(match: re.Match[str]) -> str:
        hyphenated_word = match.group(0)
        parts = hyphenated_word.split(STANDARD_SYLLABLE_DELIMITER)
        if len(parts) >= 3 and all(1 <= len(part) <= 5 for part in parts):
            return "".join(parts)
        return hyphenated_word

    return HYPHENATED_WORD_PATTERN.sub(remove_syllable_hyphens, cleaned_text)


def normalize_text(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t\f\v]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def adapt_text(text: str) -> str:
    adapted_tokens: list[str] = []
    for token in TOKEN_PATTERN.findall(text):
        if is_adaptable_word(token):
            adapted_tokens.append(STANDARD_SYLLABLE_DELIMITER.join(split_word_to_syllables(token)))
        else:
            adapted_tokens.append(token)
    return "".join(adapted_tokens)


def extract_adapted_words(text: str) -> list[AdaptedWord]:
    adapted_words: list[AdaptedWord] = []
    for token in TOKEN_PATTERN.findall(text):
        if not is_adaptable_word(token):
            continue

        syllables = split_word_to_syllables(token)
        language = detect_language_hint(token)
        adapted_words.append(
            AdaptedWord(
                original=token,
                adapted=STANDARD_SYLLABLE_DELIMITER.join(syllables),
                syllables=syllables,
                language_hint=language,
                vowel_harmony=detect_kazakh_vowel_harmony(token) if language == "kk" else None,
            )
        )
    return adapted_words


def is_adaptable_word(token: str) -> bool:
    return any(character in CYRILLIC_LETTERS for character in token)


def detect_language_hint(word: str) -> str:
    if any(character in KAZAKH_SPECIFIC_LETTERS for character in word):
        return "kk"
    if any(character in CYRILLIC_LETTERS for character in word):
        return "ru"
    return "unknown"


def detect_kazakh_vowel_harmony(word: str) -> str:
    has_front = any(character in KAZAKH_FRONT_VOWELS for character in word)
    has_back = any(character in KAZAKH_BACK_VOWELS for character in word)

    if has_front and has_back:
        return "mixed"
    if has_front:
        return "front"
    if has_back:
        return "back"
    return "neutral"


def split_word_to_syllables(word: str) -> list[str]:
    word = remove_existing_syllable_markup(word)
    vowel_positions = [
        index
        for index, character in enumerate(word)
        if character in ALL_CYRILLIC_VOWELS
    ]

    if len(vowel_positions) <= 1:
        return [word]

    split_indices: list[int] = []
    for left_vowel, right_vowel in zip(vowel_positions, vowel_positions[1:]):
        consonant_cluster_length = right_vowel - left_vowel - 1
        split_index = choose_split_index(
            left_vowel=left_vowel,
            right_vowel=right_vowel,
            consonant_cluster_length=consonant_cluster_length,
        )
        if 0 < split_index < len(word):
            split_indices.append(split_index)

    syllables: list[str] = []
    start = 0
    for split_index in sorted(set(split_indices)):
        syllable = word[start:split_index]
        if syllable:
            syllables.append(syllable)
        start = split_index

    tail = word[start:]
    if tail:
        syllables.append(tail)

    return syllables or [word]


def choose_split_index(left_vowel: int, right_vowel: int, consonant_cluster_length: int) -> int:
    if consonant_cluster_length <= 1:
        return left_vowel + 1
    return left_vowel + 2
