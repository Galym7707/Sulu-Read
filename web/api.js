// Backend client + repository, ported from data/ApiClient.kt, MainActivity's SuluReadApiClient
// and domain/repository/SuluReadRepository.kt. Same-origin /api/* is rewritten by Vercel to the
// Hugging Face backend (see vercel.json), which is how the browser avoids CORS.
"use strict";

const API_BASE = "/api";
const FETCH_TIMEOUT_MS = 25000;        // small JSON reads
const UPLOAD_TIMEOUT_MS = 270000;      // adaptation uploads
const ADAPTATION_TIMEOUT_MS = 300000;  // whole-adaptation budget, as in the app
const MAX_UPLOAD_IMAGE_SIDE = 1800;
const UPLOAD_JPEG_QUALITY = 0.86;

function fetchWithTimeout(url, options = {}, timeoutMs = FETCH_TIMEOUT_MS, externalSignal = null) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  if (externalSignal) {
    if (externalSignal.aborted) controller.abort();
    else externalSignal.addEventListener("abort", () => controller.abort(), { once: true });
  }
  return fetch(url, { ...options, signal: controller.signal }).finally(() => clearTimeout(timer));
}

async function apiJson(path, { method = "GET", body = null, timeoutMs = FETCH_TIMEOUT_MS, signal = null } = {}) {
  const options = { method, headers: { Accept: "application/json" } };
  if (body !== null) {
    options.headers["Content-Type"] = "application/json; charset=utf-8";
    options.body = JSON.stringify(body);
  }
  const response = await fetchWithTimeout(`${API_BASE}${path}`, options, timeoutMs, signal);
  const text = await response.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* handled below */ }
  if (!response.ok) {
    const message = (json && (json.message || json.detail)) || `HTTP ${response.status}`;
    throw new Error(typeof message === "string" ? message : `HTTP ${response.status}`);
  }
  if (json === null) throw new Error("Bad JSON from backend");
  return json;
}

function optStringOrNull(value) {
  if (value === null || value === undefined) return null;
  const s = String(value);
  return s.trim() && s !== "null" ? s : null;
}

/* ---------------- Adaptation (SuluReadApiClient) ---------------- */

function parseAdaptResponse(json) {
  if (json.status === "success") {
    const adaptedText = json.adapted_text || "";
    if (!adaptedText.trim()) return { kind: "error", message: t("api_empty_adapted_text") };
    return {
      kind: "text",
      adaptedText,
      originalText: json.original_text || "",
      source: optStringOrNull(json.source) || "material",
      wordCount: json.word_count || 0,
      title: optStringOrNull(json.title),
      words: (json.words || []).map((w) => ({
        original: w.original || "", languageHint: optStringOrNull(w.language_hint)
      })).filter((w) => w.original)
    };
  }
  return { kind: "error", message: optStringOrNull(json.message) || t("api_adaptation_failed") };
}

function parseBookResponse(json) {
  if (json.status === "success") {
    const pages = (json.pages || []).map((p, i) => ({
      pageNumber: p.page_number || i + 1,
      text: p.original_text || "",
      wordCount: p.word_count || 0
    })).filter((p) => p.text.trim());
    if (pages.length > 0) {
      return {
        kind: "book",
        pages,
        title: optStringOrNull(json.title),
        truncated: Boolean(json.truncated),
        ocrPageNumbers: json.ocr_page_numbers || []
      };
    }
  }
  return { kind: "error", message: optStringOrNull(json.message) || t("file_adaptation_failed") };
}

async function adaptUrl(url, languageHint, signal) {
  try {
    const json = await apiJson("/v1/adapt-url", {
      method: "POST", body: { url, language_hint: languageHint },
      timeoutMs: ADAPTATION_TIMEOUT_MS, signal
    });
    return parseAdaptResponse(json);
  } catch (e) {
    if (e.name === "AbortError") return { kind: "error", message: t("api_adaptation_timeout") };
    return { kind: "error", message: t("api_connection_error") };
  }
}

// Downscale + re-encode a photo exactly as the app does (longest side 1800, JPEG q0.86).
async function prepareImageUpload(file) {
  const bitmap = await createImageBitmap(file);
  const longest = Math.max(bitmap.width, bitmap.height);
  const scale = longest > MAX_UPLOAD_IMAGE_SIDE ? MAX_UPLOAD_IMAGE_SIDE / longest : 1;
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext("2d").drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", UPLOAD_JPEG_QUALITY));
  if (!blob) throw new Error("encode failed");
  let name = (file.name || "textbook-page.jpg").replace(/[\r\n"\\]/g, "_");
  if (!/\.jpe?g$/i.test(name)) name = name.replace(/\.[^.]*$/, "") + ".jpg";
  return { blob, name };
}

async function adaptImage(file, languageHint, signal) {
  let upload;
  try { upload = await prepareImageUpload(file); }
  catch { return { kind: "error", message: t("image_prepare_error") }; }
  const form = new FormData();
  form.append("file", upload.blob, upload.name);
  form.append("language_hint", languageHint);
  try {
    const response = await fetchWithTimeout(`${API_BASE}/v1/adapt-image`, {
      method: "POST", body: form, headers: { Accept: "application/json" }
    }, ADAPTATION_TIMEOUT_MS, signal);
    return parseAdaptResponse(await response.json());
  } catch (e) {
    if (e.name === "AbortError") return { kind: "error", message: t("api_adaptation_timeout") };
    return { kind: "error", message: t("api_connection_error") };
  }
}

// Book files go up untouched — no re-encode, that would corrupt a PDF.
async function adaptFile(file, languageHint, signal) {
  if (!file || file.size === 0) return { kind: "error", message: t("file_prepare_error") };
  const form = new FormData();
  form.append("file", file, (file.name || "book").replace(/[\r\n"\\]/g, "_"));
  form.append("language_hint", languageHint);
  try {
    const response = await fetchWithTimeout(`${API_BASE}/v1/adapt-file`, {
      method: "POST", body: form, headers: { Accept: "application/json" }
    }, ADAPTATION_TIMEOUT_MS, signal);
    return parseBookResponse(await response.json());
  } catch (e) {
    if (e.name === "AbortError") return { kind: "error", message: t("api_adaptation_timeout") };
    return { kind: "error", message: t("api_connection_error") };
  }
}

async function fetchCatalog(languageHint) {
  try {
    const json = await apiJson(`/v1/catalog?language_hint=${encodeURIComponent(languageHint)}`);
    return (json.books || []).map((item) => ({
      id: item.id || "",
      title: optStringOrNull(item.title) || "",
      author: item.author || "",
      language: item.language || "",
      grade: item.grade || 0,
      pageCount: item.page_count || 0,
      wordCount: item.word_count || 0,
      sourceUrl: item.source_url || "",
      workLicense: item.work_license || "",
      editionLicense: item.edition_license || ""
    })).filter((b) => b.id);
  } catch {
    return null; // "could not reach the library", distinct from an empty shelf
  }
}

async function fetchCatalogBook(bookId) {
  try {
    const json = await apiJson(`/v1/catalog/${encodeURIComponent(bookId)}`);
    const pages = (json.pages || []).map((p, i) => {
      const text = p.text || "";
      return {
        pageNumber: p.page_number || i + 1,
        text,
        wordCount: text.split(/\s+/).filter(Boolean).length
      };
    }).filter((p) => p.text.trim());
    if (pages.length === 0) return { kind: "error", message: t("api_connection_error") };
    return { kind: "book", pages, title: optStringOrNull(json.title), truncated: false, ocrPageNumbers: [] };
  } catch {
    return { kind: "error", message: t("api_connection_error") };
  }
}

// Returns null for anything that is not a concrete noun — an ordinary answer, not an error.
async function fetchWordPicture(word, languageHint) {
  try {
    const json = await apiJson(
      `/v1/word-picture?word=${encodeURIComponent(word)}&language_hint=${encodeURIComponent(languageHint)}`
    );
    if (!json.found) return null;
    return {
      word: optStringOrNull(json.word) || word,
      imageUrl: optStringOrNull(json.image_url) || "",
      attribution: optStringOrNull(json.attribution) || "",
      licenseName: optStringOrNull(json.license_name) || ""
    };
  } catch {
    return null;
  }
}

/* ---------------- Users / exercises / progress (ApiClient + Repository) ---------------- */

function parseSkillProfile(json) {
  return {
    phonologicalSkill: json.phonological_skill || 0,
    decodingFluency: json.decoding_fluency || 0,
    visualTracking: json.visual_tracking || 0,
    currentDifficulty: json.current_difficulty || 0
  };
}

function parseUser(json) {
  return {
    userId: json.user_id || "",
    displayName: json.display_name || "",
    username: optStringOrNull(json.username),
    age: typeof json.age === "number" ? json.age : null,
    languagePreference: json.language_preference || "",
    skillProfile: parseSkillProfile(json.skill_profile || {})
  };
}

const storage = {
  get userId() { try { return localStorage.getItem("sulu_user_id"); } catch { return null; } },
  set userId(v) { try { localStorage.setItem("sulu_user_id", v); } catch { /* private mode */ } },
  get languageCode() { try { return localStorage.getItem("sulu_language"); } catch { return null; } },
  set languageCode(v) { try { localStorage.setItem("sulu_language", v); } catch { /* private mode */ } },
  get theme() { try { return localStorage.getItem("sulu_theme"); } catch { return null; } },
  set theme(v) { try { localStorage.setItem("sulu_theme", v); } catch { /* private mode */ } },
  get pendingAttempts() {
    try { return JSON.parse(localStorage.getItem("sulu_pending_attempts") || "[]"); } catch { return []; }
  },
  set pendingAttempts(v) {
    try { localStorage.setItem("sulu_pending_attempts", JSON.stringify(v)); } catch { /* private mode */ }
  }
};

async function ensureUser() {
  const existing = storage.userId;
  if (existing) {
    try { return parseUser(await apiJson(`/v1/users/${encodeURIComponent(existing)}`)); }
    catch { /* fall through to anonymous */ }
  }
  const languageCode = window.APP_STATE.languageCode;
  const user = parseUser(await apiJson("/v1/users", {
    method: "POST",
    body: {
      display_name: langFromCode(languageCode).defaultDisplayName,
      age: null,
      language_preference: languageCode
    }
  }));
  storage.userId = user.userId;
  return user;
}

async function registerUser(username, password, displayName) {
  const user = parseUser(await apiJson("/v1/users/register", {
    method: "POST",
    body: {
      username, password,
      display_name: displayName || username,
      age: null,
      language_preference: window.APP_STATE.languageCode
    }
  }));
  storage.userId = user.userId;
  return user;
}

async function loginUser(username, password) {
  const user = parseUser(await apiJson("/v1/users/login", { method: "POST", body: { username, password } }));
  storage.userId = user.userId;
  storage.languageCode = user.languagePreference;
  return user;
}

async function updateUserLanguage(userId, languagePreference) {
  return parseUser(await apiJson(`/v1/users/${encodeURIComponent(userId)}/language`, {
    method: "PATCH", body: { language_preference: languagePreference }
  }));
}

async function generateExercises(userId, sourceWords, count, languageCode) {
  const list = await apiJson("/v1/exercises/generate", {
    method: "POST",
    body: {
      user_id: userId,
      source_words: sourceWords,
      exercise_type: "mixed",
      count: Math.max(1, Math.min(10, count)),
      language_hint: backendHintFor(languageCode)
    }
  });
  return list.map((json) => ({
    exerciseId: json.exercise_id || "",
    type: json.type || "",
    subExercise: optStringOrNull(json.sub_exercise),
    prompt: json.prompt || "",
    targetWord: json.target_word || "",
    options: json.options || [],
    correctAnswer: json.correct_answer || "",
    difficultyLevel: json.difficulty_level || 0,
    languageHint: json.language_hint || ""
  }));
}

function normalizeAnswer(answer) {
  return answer.trim().toLowerCase().split(" ").join("");
}

function attemptPayload(userId, exercise, userAnswer, responseTimeMs) {
  return {
    user_id: userId,
    exercise_type: exercise.type,
    sub_exercise: exercise.subExercise,
    target_word: exercise.targetWord,
    correct_answer: exercise.correctAnswer,
    user_answer: userAnswer,
    response_time_ms: responseTimeMs,
    difficulty_level: exercise.difficultyLevel,
    language_hint: exercise.languageHint
  };
}

// Failed submissions queue locally and sync later — the web analog of the app's WorkManager queue.
async function submitExerciseAttempt(userId, exercise, userAnswer, responseTimeMs) {
  try {
    const json = await apiJson("/v1/exercises/attempt", {
      method: "POST", body: attemptPayload(userId, exercise, userAnswer, responseTimeMs)
    });
    return {
      isCorrect: Boolean(json.is_correct),
      updatedDifficulty: json.updated_difficulty || 0,
      skillProfile: parseSkillProfile(json.skill_profile || {}),
      feedback: json.feedback || "",
      isPendingSync: false
    };
  } catch {
    const pending = storage.pendingAttempts;
    pending.push(attemptPayload(userId, exercise, userAnswer, responseTimeMs));
    storage.pendingAttempts = pending;
    notifyPendingCount();
    return {
      isCorrect: normalizeAnswer(exercise.correctAnswer) === normalizeAnswer(userAnswer),
      updatedDifficulty: exercise.difficultyLevel,
      skillProfile: { phonologicalSkill: 0.5, decodingFluency: 0.5, visualTracking: 0.5, currentDifficulty: exercise.difficultyLevel },
      feedback: "",
      isPendingSync: true
    };
  }
}

let pendingCountListeners = [];
function onPendingCountChange(listener) { pendingCountListeners.push(listener); }
function notifyPendingCount() {
  const count = storage.pendingAttempts.length;
  pendingCountListeners.forEach((l) => l(count));
}

async function syncPendingAttempts() {
  const pending = storage.pendingAttempts;
  if (pending.length === 0) return;
  const stillPending = [];
  for (const payload of pending) {
    try { await apiJson("/v1/exercises/attempt", { method: "POST", body: payload }); }
    catch { stillPending.push(payload); }
  }
  storage.pendingAttempts = stillPending;
  notifyPendingCount();
}
window.addEventListener("online", () => { syncPendingAttempts(); });

async function getProgress(userId) {
  const json = await apiJson(`/v1/progress/${encodeURIComponent(userId)}`);
  return {
    userId: json.user_id || "",
    totalExercises: json.total_exercises || 0,
    exerciseAccuracy: json.exercise_accuracy || 0,
    averageResponseTimeMs: json.average_response_time_ms || 0,
    latestSupportLevel: optStringOrNull(json.latest_support_level),
    skillProfile: parseSkillProfile(json.skill_profile || {}),
    recentScreenings: json.recent_screenings || [],
    dailyActivity: json.daily_activity || [],
    dailyWpm: json.daily_wpm || []
  };
}

async function simplifyText(text, languageCode) {
  const json = await apiJson("/v1/simplify", {
    method: "POST", body: { text, language_hint: backendHintFor(languageCode) },
    timeoutMs: UPLOAD_TIMEOUT_MS
  });
  return json.simplified_text || "";
}

// Task prompts are verbatim from AiRepository.kt — the backend contract is the prompt text.
async function generateAi(task, text, languageCode, level = null, mode = "explain", extra = {}) {
  const json = await apiJson("/ai/generate", {
    method: "POST",
    body: { task, text, language: backendHintFor(languageCode), level, mode, extra },
    timeoutMs: UPLOAD_TIMEOUT_MS
  });
  if (!json.success) throw new Error(json.error || "AI help is unavailable.");
  const result = optStringOrNull(json.result);
  if (!result) throw new Error("AI returned an empty response.");
  return result;
}

function explainTextWithAi(text, languageCode) {
  return generateAi(
    "Explain the selected reading text clearly and briefly.",
    text, languageCode, null, "reading_help"
  );
}
function hintForWord(word, languageCode) {
  return generateAi(
    "A child with dyslexia is stuck on one word while reading aloud. " +
    "Answer in at most two very short lines. " +
    "Line 1: a concrete sensory clue to what the word means, the kind of thing " +
    "you could picture or touch. Line 2: one everyday synonym. " +
    "No definitions, no grammar terms, no encouragement, no extra words.",
    word, languageCode, null, "reading_help"
  );
}
