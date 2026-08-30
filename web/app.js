// Sulu Read web app — functional port of the Android app's UI layer:
// MainActivity.kt (SuluReadRoute + screens), PremiumReadingScreen.kt, FocusReaderScreen.kt,
// TrainingScreen.kt, ProgressScreen.kt, ProfileScreen.kt, SuluReadNavGraph.kt.
"use strict";

/* ---------------- tiny DOM helper ---------------- */

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (key === "class") node.className = value;
    else if (key === "style") node.style.cssText = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key === "html") node.innerHTML = value;
    else node.setAttribute(key, value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child.nodeType ? child : document.createTextNode(String(child)));
  }
  return node;
}

/* ---------------- global state ---------------- */

window.APP_STATE = {
  languageCode: storage.languageCode || defaultLangCode(),
  user: null,             // UserProfile | null
  userLoading: true,
  userError: false,
  authFeedback: null,     // {key, isError}
  tab: "reader",
  trainingSourceWords: []
};
const S = window.APP_STATE;

const tabContainers = {};

function switchTab(tab) {
  S.tab = tab;
  for (const [name, container] of Object.entries(tabContainers)) {
    container.classList.toggle("hidden", name !== tab);
  }
  document.querySelectorAll("nav.bottom button").forEach((b) => {
    b.classList.toggle("active", b.dataset.tab === tab);
  });
  if (tab === "progress") progressScreen.onShow();
  if (tab === "training") trainingScreen.render();
  if (tab === "settings") settingsScreen.render();
}

function createTrainingFromWords(words) {
  S.trainingSourceWords = words;
  trainingScreen.reset();
  switchTab("training");
}

async function loadUser() {
  S.userLoading = true; S.userError = false;
  settingsScreen.render(); trainingScreen.render();
  try {
    S.user = await ensureUser();
    S.userError = false;
  } catch {
    S.user = null; S.userError = true;
  }
  S.userLoading = false;
  settingsScreen.render(); trainingScreen.render();
  if (S.tab === "progress") progressScreen.onShow();
}

/* ---------------- overlays: sheets and dialogs ---------------- */

function openOverlay(content, { centered = false, onDismiss = null } = {}) {
  const overlay = el("div", { class: "overlay" + (centered ? " center-dialog" : "") }, content);
  overlay.addEventListener("click", (e) => {
    if (e.target === overlay) { overlay.remove(); if (onDismiss) onDismiss(); }
  });
  document.body.append(overlay);
  return overlay;
}

// ProcessingErrorDialog: retry when a request is retryable, otherwise just acknowledge.
function showErrorDialog(message, { canRetry = false, onRetry = null, onCancel = null } = {}) {
  let overlay;
  const dialog = el("div", { class: "dialog stack" },
    el("h3", { class: "title-lg" }, t("processing_error_title")),
    el("div", { class: "body-md" }, message),
    el("div", { class: "row row--end" },
      el("button", { class: "text-btn", onclick: () => { overlay.remove(); if (onCancel) onCancel(); } }, t("cancel")),
      el("button", {
        class: "text-btn",
        onclick: () => { overlay.remove(); if (canRetry && onRetry) onRetry(); }
      }, canRetry ? t("try_again") : t("ok"))
    )
  );
  overlay = openOverlay(dialog, { centered: true, onDismiss: onCancel });
}

/* =========================================================================
   READER TAB — SuluReadRoute state machine
   ========================================================================= */

const readerScreen = {
  container: null,
  state: { kind: "home" },   // home | loading | catalog | reading | readingBook
  webLink: "",
  documentStatus: null,      // {text, source} source: camera|gallery|file|null
  pendingRequest: null,      // {run: () => void} retained for the retry dialog
  abortController: null,
  catalogBooks: [], catalogLoading: false, catalogFailed: false,
  aiState: { kind: "idle" }, // idle | loading | success | error
  premium: null,             // active PremiumReading instance
  focus: null,               // active FocusReader instance

  setState(state) {
    this.destroyReaders();
    this.state = state;
    this.render();
  },

  destroyReaders() {
    if (this.premium) { this.premium.destroy(); this.premium = null; }
    if (this.focus) { this.focus.destroy(); this.focus = null; }
  },

  cancelAdaptation() {
    if (this.abortController) this.abortController.abort();
    this.abortController = null;
    this.pendingRequest = null;
    this.setState({ kind: "home" });
  },

  runAdaptation(request) {
    if (this.abortController) this.abortController.abort();
    const controller = new AbortController();
    this.abortController = controller;
    this.pendingRequest = request;
    this.setState({ kind: "loading" });

    request.run(controller.signal).then((result) => {
      if (controller !== this.abortController) return; // superseded or cancelled
      this.abortController = null;
      if (result.kind === "text") {
        this.pendingRequest = null;
        this.setState({ kind: "reading", data: result, isFocusMode: false });
      } else if (result.kind === "book") {
        this.pendingRequest = null;
        this.setState({ kind: "readingBook", data: result, pageIndex: 0 });
      } else {
        this.setState({ kind: "home" });
        showErrorDialog(result.message, {
          canRetry: true,
          onRetry: () => this.runAdaptation(request),
          onCancel: () => this.cancelAdaptation()
        });
      }
    });
  },

  adaptCurrentInput() {
    const link = this.webLink.trim();
    if (!link) {
      showErrorDialog(t("input_missing_link_or_scan"), { canRetry: false });
      return;
    }
    this.documentStatus = null;
    this.runAdaptation({ run: (signal) => adaptUrl(link, backendHintFor(S.languageCode), signal) });
  },

  processImageFile(file, source) {
    this.documentStatus = {
      text: source === "camera" ? t("document_status_camera_ready") : t("document_status_gallery_ready"),
      source
    };
    this.runAdaptation({ run: (signal) => adaptImage(file, backendHintFor(S.languageCode), signal) });
  },

  processBookFile(file) {
    this.documentStatus = { text: t("document_status_file_ready"), source: "file" };
    this.runAdaptation({ run: (signal) => adaptFile(file, backendHintFor(S.languageCode), signal) });
  },

  // iOS Safari refuses .click() on an <input> that is not in the document, and action()
  // (app.js:333) does `overlay.remove(); onclick();` — so the DOM is mutated between the tap
  // and the click, which is the documented pattern that silently does nothing. One helper
  // because pickImage and pickBookFile had the identical bug; fixing only the one that was
  // reported would leave "open a book file" broken on the same phone.
  openFilePicker(attrs, onFile, missingKey) {
    const input = el("input", { ...attrs, style: "position:fixed;left:-9999px;opacity:0" });
    document.body.append(input);
    input.addEventListener("change", () => {
      const file = input.files && input.files[0];
      input.remove();
      if (file) onFile(file);
      else { this.documentStatus = { text: t(missingKey), source: null }; this.render(); }
    });
    // Not every engine fires change on cancel; without this the hidden input would leak.
    input.addEventListener("cancel", () => input.remove());
    input.click();
  },

  pickImage(source) {
    const attrs = { type: "file", accept: "image/*" };
    if (source === "camera") attrs.capture = "environment";
    this.openFilePicker(attrs, (file) => this.processImageFile(file, source), "photo_not_selected");
  },

  pickBookFile() {
    this.openFilePicker({
      type: "file",
      accept: ".pdf,.docx,.epub,.fb2,.txt,.html,application/pdf,application/epub+zip,text/plain,text/html,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/octet-stream"
    }, (file) => this.processBookFile(file), "file_not_selected");
  },

  openCatalog() {
    this.setState({ kind: "catalog" });
    this.catalogLoading = true; this.catalogFailed = false;
    this.render();
    fetchCatalog(backendHintFor(S.languageCode)).then((books) => {
      this.catalogBooks = books || [];
      this.catalogFailed = books === null;
      this.catalogLoading = false;
      if (this.state.kind === "catalog") this.render();
    });
  },

  openBook(book) {
    this.setState({ kind: "loading" });
    this.pendingRequest = null;
    fetchCatalogBook(book.id).then((result) => {
      if (this.state.kind !== "loading") return;
      if (result.kind === "book") {
        this.setState({ kind: "readingBook", data: { ...result, title: book.title }, pageIndex: 0 });
      } else {
        this.setState({ kind: "catalog" });
        showErrorDialog(result.message, { canRetry: false });
      }
    });
  },

  /* ---- AI help plumbing (AiHelpViewModel) ---- */
  setAiState(state) {
    this.aiState = state;
    if (this.premium) this.premium.onAiStateChanged(state);
    if (this.focus) this.focus.onAiStateChanged(state);
  },
  explainTextWithAi(text) {
    this.setAiState({ kind: "loading" });
    explainTextWithAi(text, S.languageCode)
      .then((result) => this.setAiState({ kind: "success", result }))
      .catch(() => this.setAiState({ kind: "error" }));
  },
  requestWordHint(word) {
    this.setAiState({ kind: "loading" });
    hintForWord(word, S.languageCode)
      .then((result) => this.setAiState({ kind: "success", result }))
      .catch(() => this.setAiState({ kind: "error" }));
  },
  dismissAiHelp() { this.setAiState({ kind: "idle" }); },

  /* ---- rendering ---- */

  render() {
    const c = this.container;
    c.replaceChildren();
    switch (this.state.kind) {
      case "home": c.append(this.renderHome()); break;
      case "loading": c.append(this.renderLoading()); break;
      case "catalog": c.append(this.renderCatalog()); break;
      case "reading": c.append(this.renderReading(this.state, true)); break;
      case "readingBook": c.append(this.renderBook()); break;
    }
  },

  renderHome() {
    // A ready document is one quiet line; only the not-ready case earns a surface. The old card
    // showed a checkmark unconditionally, so "no photo selected" still rendered a tick.
    const statusNode = this.documentStatus === null ? null : (() => {
      const isReady = this.documentStatus.source !== null;
      const sourceText =
        this.documentStatus.source === "camera" ? t("document_status_source_camera") :
        this.documentStatus.source === "gallery" ? t("document_status_source_gallery") :
        this.documentStatus.source === "file" ? t("document_status_source_file") :
        t("document_status_no_photo");
      const line = (this.documentStatus.text || "") + " — " + sourceText;
      if (isReady) {
        return el("div", { class: "row s-2" }, icon("check", 18), el("span", { class: "body-sm muted" }, line));
      }
      return el("div", { class: "card warn row row--top s-2" },
        icon("alert", 20), el("span", { class: "body-md" }, line));
    })();

    const linkInput = el("input", {
      class: "field", type: "url", placeholder: "https://", value: this.webLink,
      oninput: (e) => {
        this.webLink = e.target.value;
        if (e.target.value.trim()) this.documentStatus = null;
        clearBtn.classList.toggle("hidden", !e.target.value);
      }
    });
    const clearBtn = el("button", {
      class: "clear-btn" + (this.webLink ? "" : " hidden"),
      "aria-label": t("web_link_clear"),
      onclick: () => { this.webLink = ""; linkInput.value = ""; clearBtn.classList.add("hidden"); }
    }, icon("close", 20));

    // The screen opens on the two things it is for. The app name, the tagline and the support
    // message were three blocks of chrome above the first control.
    return el("div", { class: "stack-lg" },
      el("div", { class: "card" },
        this.listRow("camera", t("home_scan_textbook"), t("home_scan_subtitle"), () => this.openDocumentSheet()),
        this.listRow("library", t("catalog_title"), t("catalog_subtitle"), () => this.openCatalog())
      ),
      statusNode,
      el("div", { class: "stack" },
        el("label", { class: "field-label" }, t("web_link_label")),
        el("div", { class: "input-wrap" }, linkInput, clearBtn),
        el("button", { class: "btn lg full", onclick: () => this.adaptCurrentInput() }, t("adapt_text_button"))
      )
    );
  },

  listRow(iconName, title, subtitle, onclick) {
    const chevron = icon("chevron-right", 20);
    chevron.classList.add("chev");
    return el("button", { class: "list-row", onclick },
      icon(iconName),
      el("span", { class: "grow" },
        el("span", { class: "title-md" }, title),
        subtitle ? el("span", { class: "body-sm muted" }, subtitle) : null
      ),
      chevron
    );
  },

  openDocumentSheet() {
    let overlay;
    const action = (iconName, title, subtitle, onclick) =>
      this.listRow(iconName, title, subtitle, () => { overlay.remove(); onclick(); });
    const sheet = el("div", { class: "sheet stack" },
      el("h2", { class: "headline" }, t("document_sheet_title")),
      el("div", { class: "body-md muted" }, t("document_sheet_subtitle")),
      el("div", { class: "card" },
        action("camera", t("document_sheet_camera_title"), t("document_sheet_camera_subtitle"), () => this.pickImage("camera")),
        action("image", t("document_sheet_gallery_title"), t("document_sheet_gallery_subtitle"), () => this.pickImage("gallery")),
        action("file-text", t("document_sheet_file_title"), t("document_sheet_file_subtitle"), () => this.pickBookFile()),
        action("library", t("catalog_title"), t("catalog_subtitle"), () => this.openCatalog())
      )
    );
    overlay = openOverlay(sheet);
  },

  renderLoading() {
    return el("div", { class: "stack s-5 center items-center" },
      el("div", { class: "spinner lg" }),
      el("div", { class: "title-md center" }, t("loading_adaptation")),
      el("button", { class: "text-btn", onclick: () => this.cancelAdaptation() }, t("cancel"))
    );
  },

  renderCatalog() {
    const body =
      this.catalogLoading ? el("div", { class: "body-lg muted" }, t("catalog_loading")) :
      this.catalogFailed ? el("div", { class: "stack" },
        el("div", { class: "body-lg muted" }, t("catalog_failed")),
        el("button", { class: "btn self-start", onclick: () => this.openCatalog() }, icon("refresh", 20), t("catalog_retry"))
      ) :
      this.catalogBooks.length === 0 ? el("div", { class: "body-lg muted" }, t("catalog_empty")) :
      // A table of contents, not ten outlined boxes: author and grade share one quiet line.
      el("div", null,
        this.catalogBooks.map((book) => {
          const chevron = icon("chevron-right", 20);
          chevron.classList.add("chev");
          return el("button", { class: "list-row", onclick: () => this.openBook(book) },
            el("span", { class: "grow" },
              el("span", { class: "title-lg" }, book.title),
              el("span", { class: "body-sm muted" },
                book.author + " · " + t("catalog_book_meta", book.grade, book.pageCount))
            ),
            chevron
          );
        })
      );

    return el("div", { class: "stack" },
      el("div", { class: "row" },
        el("button", {
          class: "icon-btn", "aria-label": t("reader_back_home"),
          onclick: () => this.setState({ kind: "home" })
        }, icon("back")),
        el("h2", { class: "headline" }, t("catalog_title"))
      ),
      body
    );
  },

  renderBook() {
    const state = this.state;
    const page = state.data.pages[state.pageIndex];
    if (!page) return el("div");
    const isOcrPage = state.data.ocrPageNumbers.includes(page.pageNumber);

    const pager = state.data.pages.length <= 1 ? null :
      el("div", { class: "row s-3" },
        el("button", {
          class: "btn grow",
          disabled: state.pageIndex === 0 ? "" : undefined,
          onclick: () => { state.pageIndex = Math.max(0, state.pageIndex - 1); this.destroyReaders(); this.render(); }
        }, t("book_previous_page")),
        el("button", {
          class: "btn grow",
          disabled: state.pageIndex >= state.data.pages.length - 1 ? "" : undefined,
          onclick: () => { state.pageIndex = Math.min(state.data.pages.length - 1, state.pageIndex + 1); this.destroyReaders(); this.render(); }
        }, t("book_next_page"))
      );

    // Each page is handed to the ordinary reader — focus mode, AI help, training all work.
    const readingState = {
      kind: "reading",
      data: {
        adaptedText: page.text, originalText: page.text, source: "file",
        wordCount: page.wordCount, title: null, words: []
      },
      isFocusMode: state.bookFocusMode || false,
      _bookHost: state
    };

    return el("div", { class: "stack" },
      el("div", { class: "row between" },
        el("div", { class: "grow" },
          el("h3", { class: "title-lg" }, state.data.title || t("book_reader_title")),
          el("div", { class: "body-sm muted" }, t("book_page_of", page.pageNumber, state.data.pages.length))
        ),
        el("button", { class: "text-btn", onclick: () => this.goHome() }, t("reader_back_home"))
      ),
      isOcrPage ? el("div", { class: "body-sm muted" }, t("book_page_from_scan")) : null,
      state.data.truncated ? el("div", { class: "body-sm muted" }, t("book_truncated_notice", state.data.pages.length)) : null,
      this.renderReading(readingState, false),
      pager
    );
  },

  goHome() {
    this.documentStatus = null;
    this.pendingRequest = null;
    this.setState({ kind: "home" });
  },

  renderReading(state, showHeader) {
    const container = el("div", { class: "stack s-4" });

    if (showHeader) {
      container.append(el("div", { class: "row between" },
        el("h3", { class: "title-lg grow" }, state.data.title || t("reader_adapted_text_title")),
        el("button", { class: "text-btn", onclick: () => this.goHome() }, t("reader_back_home"))
      ));
    }

    const setFocusMode = (on) => {
      if (state._bookHost) state._bookHost.bookFocusMode = on;
      else state.isFocusMode = on;
      this.destroyReaders();
      this.render();
    };

    if (state.isFocusMode) {
      // Focus mode is fed the original text: scene splitting needs real punctuation.
      const focusHost = el("div");
      container.append(focusHost);
      this.focus = new FocusReader(focusHost, {
        text: state.data.originalText.trim() ? state.data.originalText : state.data.adaptedText,
        languageCode: S.languageCode,
        getAiState: () => this.aiState,
        onRequestMeaningHint: (word) => this.requestWordHint(word),
        onDismissHint: () => this.dismissAiHelp(),
        onCollectTriggerWords: (words) => createTrainingFromWords(words)
      });
      container.append(el("button", { class: "text-btn self-start", onclick: () => setFocusMode(false) }, t("focus_mode_exit")));
    } else {
      container.append(el("button", { class: "text-btn self-start", onclick: () => setFocusMode(true) }, t("focus_mode_enter")));
      const premiumHost = el("div");
      container.append(premiumHost);
      this.premium = new PremiumReading(premiumHost, {
        text: state.data.adaptedText,
        backendWords: state.data.words,
        languageCode: S.languageCode,
        onSimplifyText: (source) => simplifyText(source, S.languageCode),
        onExplainTextWithAi: (text) => this.explainTextWithAi(text),
        onDismissAiHelp: () => this.dismissAiHelp(),
        onLookUpWordPicture: (word) => fetchWordPicture(word, backendHintFor(S.languageCode))
      });
      // Below the text on purpose: building a training set happens after the reading.
      container.append(el("button", {
        class: "text-btn self-start",
        onclick: () => createTrainingFromWords(
          extractTrainingWords(state.data.originalText.trim() ? state.data.originalText : state.data.adaptedText)
        )
      }, t("reader_create_training_from_text")));
    }
    return container;
  }
};

/* =========================================================================
   PREMIUM READING — PremiumReadingScreen.kt
   ========================================================================= */

const NO_PLAYING_WORD = -1;

class PremiumReading {
  constructor(host, options) {
    this.host = host;
    this.options = options;
    this.letterSpacing = 1.5;
    this.lineHeight = 34;
    this.rulerEnabled = false;
    this.rulerY = 0;
    this.playingIndex = NO_PLAYING_WORD;
    this.playToken = 0;
    this.destroyed = false;

    // buildReadingParagraphs: backend words as one paragraph, else split on newlines.
    if (options.backendWords && options.backendWords.length > 0) {
      this.paragraphs = [{
        original: options.text,
        words: options.backendWords.map((w, index) => ({ index, word: w }))
      }];
    } else {
      let nextIndex = 0;
      this.paragraphs = options.text.split(/(?:\r?\n){1,}/).map((raw) => {
        const text = raw.trim();
        if (!text) return null;
        return {
          original: text,
          words: text.split(/\s+/).filter(Boolean).map((tok) => ({
            index: nextIndex++, word: { original: tok, languageHint: null }
          }))
        };
      }).filter(Boolean);
    }
    this.words = this.paragraphs.flatMap((p) => p.words);
    this.render();
  }

  destroy() {
    this.destroyed = true;
    this.stopPlayback();
  }

  onAiStateChanged(state) {
    if (state.kind === "idle") { if (this.aiOverlay) { this.aiOverlay.remove(); this.aiOverlay = null; } return; }
    this.showAiSheet(state);
  }

  render() {
    this.host.replaceChildren();

    // The icon is its own node so refreshHighlight() can swap it without rewriting the label.
    this.playIcon = icon("play", 20);
    const playBtn = el("button", { class: "btn grow", onclick: () => this.togglePlayback() },
      this.playIcon, el("span", null, t("reader_listen")));
    this.playBtn = playBtn;

    const quickBar = el("div", { class: "row" },
      playBtn,
      el("button", {
        class: "icon-btn accent", "aria-label": t("reader_ai_help"),
        onclick: () => this.options.onExplainTextWithAi(this.options.text)
      }, icon("sparkle")),
      el("button", {
        class: "icon-btn", "aria-label": t("reader_settings"),
        onclick: () => this.openSettingsSheet()
      }, icon("sliders"))
    );

    const flow = el("div", { class: "reading-flow" });
    this.wordNodes = [];
    for (const paragraph of this.paragraphs) {
      const pNode = el("div", { class: "reading-paragraph" });
      this.attachLongPress(pNode, () => this.requestSimplification(paragraph.original));
      for (const { index, word } of paragraph.words) {
        const wordNode = el("span", {
          class: "reader-word",
          onclick: () => this.onWordClick(index, word.original)
        }, word.original);
        this.attachLongPress(wordNode, () => this.requestSimplification(paragraph.original));
        this.wordNodes[index] = wordNode;
        pNode.append(wordNode);
      }
      flow.append(pNode);
    }

    this.surface = el("div", { class: "reading-surface" }, flow);
    this.applyTextStyle();
    if (this.rulerEnabled) this.attachRuler();

    this.host.append(el("div", { class: "stack" }, quickBar, this.surface));
    this.refreshHighlight();
  }

  attachLongPress(node, callback) {
    let timer = null;
    const start = () => { timer = setTimeout(() => { timer = null; callback(); }, 550); };
    const clear = () => { if (timer) { clearTimeout(timer); timer = null; } };
    node.addEventListener("pointerdown", start);
    node.addEventListener("pointerup", clear);
    node.addEventListener("pointerleave", clear);
    node.addEventListener("contextmenu", (e) => e.preventDefault());
  }

  // Two custom properties on the surface, inherited by every word — one write per slider tick
  // instead of one per word node.
  applyTextStyle() {
    if (!this.surface) return;
    this.surface.style.setProperty("--reader-tracking", this.letterSpacing + "px");
    this.surface.style.setProperty("--reader-leading", this.lineHeight + "px");
  }

  refreshHighlight() {
    this.wordNodes.forEach((node, i) => {
      if (node) node.classList.toggle("playing", i === this.playingIndex);
    });
    if (this.playIcon) {
      const next = icon(this.playingIndex === NO_PLAYING_WORD ? "play" : "stop", 20);
      this.playIcon.replaceWith(next);
      this.playIcon = next;
    }
  }

  onWordClick(index, tapped) {
    // Tapping a word reads it aloud and, when it is a noun, shows what it means.
    this.playFrom(index);
    this.options.onLookUpWordPicture(tapped).then((picture) => {
      if (this.destroyed || !picture) return; // no picture is an ordinary answer, nothing appears
      this.showPictureDialog(tapped, picture);
    });
  }

  togglePlayback() {
    if (this.playingIndex !== NO_PLAYING_WORD) this.stopPlayback();
    else this.playFrom(this.playingIndex >= 0 && this.playingIndex < this.words.length ? this.playingIndex : 0);
  }

  stopPlayback() {
    this.playToken += 1;
    ttsStop();
    this.playingIndex = NO_PLAYING_WORD;
    this.refreshHighlight();
  }

  // Speak from a word to the end, chunked by detected language, highlight following along.
  // Boundary events drive the highlight when the voice provides them; a timed loop covers the
  // voices that do not — the same dual scheme as the app's onRangeStart + timed fallback.
  async playFrom(startIndex) {
    this.playToken += 1;
    const token = this.playToken;
    ttsStop();
    if (this.words.length === 0) return;
    startIndex = Math.max(0, Math.min(startIndex, this.words.length - 1));
    this.playingIndex = startIndex;
    this.refreshHighlight();

    // Build language chunks like buildUtteranceTracking.
    const chunks = [];
    let current = null;
    for (let i = startIndex; i < this.words.length; i++) {
      const word = this.words[i].word;
      const lang = (word.languageHint && APP_LANGUAGES.some((l) => l.code === word.languageHint))
        ? word.languageHint
        : detectSpeechLanguageCode(word.original, (current && current.lang) || this.options.languageCode);
      if (!current || current.lang !== lang) {
        current = { lang, text: "", ranges: [] };
        chunks.push(current);
      }
      if (current.text) current.text += " ";
      const start = current.text.length;
      current.text += word.original;
      current.ranges.push({ wordIndex: i, start, end: current.text.length });
    }

    let boundarySeen = false;
    for (const chunk of chunks) {
      if (token !== this.playToken) return;
      const done = new Promise((resolve) => {
        ttsSpeak(chunk.text, {
          languageCode: chunk.lang, rate: 0.88, flush: false,
          onboundary: (event) => {
            if (token !== this.playToken) return;
            if (typeof event.charIndex !== "number") return;
            const range = chunk.ranges.find((r) => event.charIndex >= r.start && event.charIndex < r.end) ||
              [...chunk.ranges].reverse().find((r) => event.charIndex >= r.start);
            if (range) {
              boundarySeen = true;
              this.playingIndex = range.wordIndex;
              this.refreshHighlight();
            }
          },
          onend: resolve
        });
      });

      // Timed fallback runs alongside; it only writes when no boundary event has been seen.
      const timedLoop = (async () => {
        for (const range of chunk.ranges) {
          if (token !== this.playToken) return;
          if (!boundarySeen) {
            this.playingIndex = range.wordIndex;
            this.refreshHighlight();
          }
          const word = this.words[range.wordIndex].word.original;
          await new Promise((r) => setTimeout(r, estimateWordDurationMillis(word)));
        }
      })();
      await Promise.race([done, timedLoop.then(() => done)]);
      await done;
    }

    if (token === this.playToken) {
      this.playingIndex = NO_PLAYING_WORD;
      this.refreshHighlight();
    }
  }

  /* ---- simplification sheet ---- */
  requestSimplification(source) {
    let overlay;
    const body = el("div", { class: "stack s-4" });
    const renderLoading = () => {
      body.replaceChildren(el("div", { class: "row" },
        el("div", { class: "spinner sm" }),
        el("div", { class: "body-lg" }, t("reader_simplify_loading"))
      ));
    };
    const renderError = () => {
      body.replaceChildren(
        el("div", { class: "row row--top s-2 text-error" }, icon("alert", 20),
          el("span", { class: "body-lg" }, t("reader_simplify_error"))),
        el("button", { class: "btn full", onclick: run }, icon("refresh", 20), t("try_again"))
      );
    };
    const run = () => {
      renderLoading();
      this.options.onSimplifyText(source)
        .then((text) => body.replaceChildren(el("div", { class: "prose" }, text)))
        .catch(renderError);
    };
    const sheet = el("div", { class: "sheet stack s-4" },
      el("div", { class: "row between" },
        el("h2", { class: "headline" }, t("reader_simplify_title")),
        el("button", { class: "icon-btn", "aria-label": t("reader_close"), onclick: () => overlay.remove() }, icon("close"))
      ),
      body,
      el("button", { class: "btn full", onclick: () => overlay.remove() }, t("reader_close"))
    );
    overlay = openOverlay(sheet);
    run();
  }

  /* ---- AI help sheet ---- */
  showAiSheet(state) {
    if (!this.aiOverlay) {
      this.aiBody = el("div", { class: "stack" });
      const sheet = el("div", { class: "sheet stack s-4" },
        el("div", { class: "row between" },
          el("h2", { class: "headline" }, t("reader_ai_title")),
          el("button", { class: "icon-btn", "aria-label": t("reader_close"), onclick: () => this.options.onDismissAiHelp() }, icon("close"))
        ),
        this.aiBody,
        el("button", { class: "btn full", onclick: () => this.options.onDismissAiHelp() }, t("reader_close"))
      );
      this.aiOverlay = openOverlay(sheet, { onDismiss: () => { this.aiOverlay = null; this.options.onDismissAiHelp(); } });
    }
    if (state.kind === "loading") {
      this.aiBody.replaceChildren(el("div", { class: "row" },
        el("div", { class: "spinner sm" }), el("div", { class: "body-lg" }, t("reader_ai_loading"))));
    } else if (state.kind === "error") {
      this.aiBody.replaceChildren(el("div", { class: "row row--top s-2 text-error" },
        icon("alert", 20), el("span", { class: "body-lg" }, t("reader_ai_error"))));
    } else if (state.kind === "success") {
      this.aiBody.replaceChildren(el("div", { class: "prose" }, state.result));
    }
  }

  /* ---- word picture dialog ---- */
  showPictureDialog(word, picture) {
    let overlay;
    const img = el("img", { src: picture.imageUrl, alt: word, class: "word-picture" });
    img.onerror = () => img.replaceWith(el("div", { class: "body-md muted" }, t("word_picture_unavailable")));
    const dialog = el("div", { class: "dialog stack" },
      el("h2", { class: "headline" }, picture.word || word),
      img,
      el("div", { class: "body-sm muted" }, t("word_picture_credit", picture.attribution, picture.licenseName)),
      el("div", { class: "row row--end" },
        el("button", { class: "text-btn", onclick: () => overlay.remove() }, t("word_picture_close"))
      )
    );
    overlay = openOverlay(dialog, { centered: true });
  }

  /* ---- settings sheet: letter spacing, line height, ruler ---- */
  openSettingsSheet() {
    let overlay;
    const slider = (labelKey, value, min, max, onChange) => {
      const valueLabel = el("span", { class: "body-md muted" }, `${Math.round(value)} px`);
      const input = el("input", {
        type: "range", min: String(min), max: String(max), step: "1", value: String(value),
        oninput: (e) => { const v = Number(e.target.value); valueLabel.textContent = `${v} px`; onChange(v); }
      });
      return el("div", { class: "stack s-1" },
        el("div", { class: "row between" }, el("span", { class: "title-md" }, t(labelKey)), valueLabel),
        input
      );
    };
    const rulerToggle = el("input", { type: "checkbox" });
    rulerToggle.checked = this.rulerEnabled;
    rulerToggle.addEventListener("change", () => {
      this.rulerEnabled = rulerToggle.checked;
      if (this.rulerEnabled) this.attachRuler(); else this.detachRuler();
    });

    const sheet = el("div", { class: "sheet stack s-3" },
      el("h3", { class: "title-lg" }, t("reader_settings")),
      slider("reader_letter_spacing", this.letterSpacing, 0, 10, (v) => { this.letterSpacing = v; this.applyTextStyle(); }),
      slider("reader_line_height", this.lineHeight, 20, 48, (v) => { this.lineHeight = v; this.applyTextStyle(); }),
      el("div", { class: "row between" },
        el("span", { class: "title-md" }, t("reader_ruler")),
        el("label", { class: "switch" }, rulerToggle, el("span", { class: "track" }))
      )
    );
    overlay = openOverlay(sheet);
  }

  attachRuler() {
    this.detachRuler();
    const band = el("div", { class: "ruler-band" });
    const overlay = el("div", { class: "ruler-overlay" }, band);
    const place = (y) => {
      const height = this.surface.clientHeight;
      const clamped = Math.max(22, Math.min(y, height - 22));
      band.style.top = (clamped - 22) + "px";
      this.rulerY = clamped;
    };
    place(this.rulerY > 0 ? this.rulerY : this.surface.clientHeight * 0.36);
    let dragging = false;
    overlay.addEventListener("pointerdown", (e) => {
      dragging = true;
      overlay.setPointerCapture(e.pointerId);
      place(e.clientY - this.surface.getBoundingClientRect().top);
    });
    overlay.addEventListener("pointermove", (e) => {
      if (dragging) place(e.clientY - this.surface.getBoundingClientRect().top);
    });
    overlay.addEventListener("pointerup", () => { dragging = false; });
    this.rulerNode = overlay;
    this.surface.append(overlay);
  }

  detachRuler() {
    if (this.rulerNode) { this.rulerNode.remove(); this.rulerNode = null; }
  }
}

/* =========================================================================
   FOCUS READER — FocusReaderScreen.kt
   ========================================================================= */

const SESSION_RESTART_DELAY_MILLIS = 120;

// Why the reading is not being checked, mapped to the string that says so plainly.
const CHECK_OFF_REASON = {
  no_api: "focus_mic_unavailable",
  no_language: "focus_check_off_language",
  installed_app: "focus_check_off_installed"
};

class FocusReader {
  constructor(host, options) {
    this.host = host;
    this.options = options;
    this.words = buildFocusWords(options.text);
    this.ladder = newLadderState();
    this.isSessionActive = false;
    this.isSpeaking = false;
    this.isFlashing = false;
    this.micDenied = false;
    this.micUnavailable = false;
    this.wasInstantFailure = false;
    this.closedTranscript = [];
    this.liveTranscript = [];
    this.visited = [0];
    this.reviewRequested = false;
    this.reviewTargets = [];
    this.review = null;
    this.heardSpeechAt = 0;
    this.destroyed = false;
    this.nudgeTimer = null;
    this.sweepTimer = null;
    this.speechGate = createSpeechGate(options.languageCode);
    // Only the server gate meters input level; the native one reports speech via partials.
    this.levelTimer = null;
    this.pendingAppSpeech = null;
    this.voiceMissing = !canSpeakLanguage(options.languageCode);
    this.ttsSilent = false;
    // Decided once, up front, not after a failed tap: on an installed iPhone app the recogniser
    // never starts, and Apple has no Kazakh recogniser at all. Focus mode then runs unchecked
    // rather than dangling a microphone button with nothing behind it.
    this.checkBlocked = speechCheckBlockReason({
      apiPresent: WebSpeechGate.available(),
      isIos: isIosDevice(),
      isStandalone: isStandaloneApp(),
      languageCode: options.languageCode
    });

    this.render();
    this.armSilenceTimers();
    this.checkFinished();
  }

  destroy() {
    this.destroyed = true;
    this.clearTimers();
    this.speechGate.release();
    ttsStop();
  }

  clearTimers() {
    if (this.levelTimer) { clearInterval(this.levelTimer); this.levelTimer = null; }
    if (this.nudgeTimer) { clearTimeout(this.nudgeTimer); this.nudgeTimer = null; }
    if (this.sweepTimer) { clearTimeout(this.sweepTimer); this.sweepTimer = null; }
  }

  currentWord() { return this.words[this.ladder.wordIndex] || null; }

  speak(value, queue = false) {
    const speechLanguage = detectSpeechLanguageCode(value, this.options.languageCode);
    // Close the open session before the first syllable so the app never hears its own voice
    // and credits the reader with the very word they were stuck on.
    // The server gate cannot pause (iOS keeps encoding through pause()), so instead of
    // stopping it we record when the app was talking and drop the chunks that overlap.
    const speechStart = Date.now();
    if (typeof this.speechGate.markAppSpeech === "function") {
      this.pendingAppSpeech = speechStart;
    } else {
      this.speechGate.stop();
    }
    this.isSpeaking = true;
    this.renderStatus();
    const utterance = ttsSpeak(value, {
      languageCode: speechLanguage,
      rate: ladderTtsRate(this.ladder),
      flush: !queue,
      onend: () => {
        if (this.destroyed) return;
        // Only the utterance queued last reopens the microphone.
        if (window.speechSynthesis && speechSynthesis.pending) return;
        if (this.pendingAppSpeech && typeof this.speechGate.markAppSpeech === "function") {
          this.speechGate.markAppSpeech(this.pendingAppSpeech, Date.now());
          this.pendingAppSpeech = null;
        }
        this.isSpeaking = false;
        this.renderStatus();
        this.maybeListen();
      }
    });
    if (utterance === null) { this.isSpeaking = false; this.renderStatus(); return; }
    // onend fires even when iOS silently drops the utterance, so "it finished" is not "it
    // played". If onstart never arrives, nothing is playing — say so rather than leaving a
    // child waiting for a word that will never come.
    ttsWatchdog(utterance, () => {
      if (this.destroyed) return;
      this.ttsSilent = true;
      this.renderStatus();
    });
  }

  // The reader owns the focus. Nothing else moves it.
  moveFocusTo(target) {
    const leaving = this.currentWord() ? this.currentWord().spoken : "";
    const next = ladderOnFocusMoved(this.ladder, target, leaving, this.words.length);
    if (next.wordIndex === this.ladder.wordIndex) return;
    if (next.wordIndex > this.ladder.wordIndex && next.wordIndex < this.words.length) {
      this.visited = [...this.visited, next.wordIndex];
    }
    this.ladder = next;
    this.armSilenceTimers();
    this.render();
    this.checkFinished();
  }

  onHelp() {
    const minimum = (this.ladder.step === FocusStep.Focus || this.ladder.step === FocusStep.Sweep)
      ? FocusStep.Letters : FocusStep.Meaning;
    this.setStep(ladderOnHelpRequested(this.ladder, minimum));
  }

  setStep(next) {
    const stepChanged = next.step !== this.ladder.step || next.wordIndex !== this.ladder.wordIndex;
    this.ladder = next;
    if (!stepChanged) return;
    this.onStepEntered();
    this.render();
  }

  onStepEntered() {
    const word = this.currentWord();
    if (!word) return;
    if (this.ladder.step === FocusStep.Sweep) {
      // Sweep: drop emphasis, flash it back after 200ms, then rest emphasised.
      this.isFlashing = false;
      this.renderTextBlock();
      this.sweepTimer = setTimeout(() => {
        this.isFlashing = true;
        this.renderTextBlock();
        this.sweepTimer = setTimeout(() => {
          this.isFlashing = false;
          this.setStep(ladderOnNudgeFinished(this.ladder));
          this.renderTextBlock();
        }, SWEEP_FLASH_MILLIS);
      }, SWEEP_FLASH_MILLIS);
    } else if (this.ladder.step === FocusStep.Letters) {
      const names = letterNamesFor(word.spoken, detectSpeechLanguageCode(word.spoken, this.options.languageCode));
      // Letters first, the whole word queued behind them — never cutting the letters off.
      this.speak(names.join(" , "));
      this.speak(word.spoken, true);
    } else if (this.ladder.step === FocusStep.Meaning) {
      this.options.onRequestMeaningHint(word.spoken);
      this.speak(word.spoken);
    }
  }

  // Silence help ladder: 5s → Sweep, 9s → Letters. Speaking restarts the wait.
  armSilenceTimers() {
    this.clearTimers();
    if (!this.currentWord()) return;
    this.nudgeTimer = setTimeout(() => {
      if (this.destroyed || this.ladder.step !== FocusStep.Focus) return;
      this.setStep(ladderOnHelpRequested(this.ladder, FocusStep.Sweep));
      this.nudgeTimer = setTimeout(() => {
        if (this.destroyed || this.ladder.step !== FocusStep.Focus) return;
        this.setStep(ladderOnHelpRequested(this.ladder, FocusStep.Letters));
      }, OFFER_HELP_AFTER_MILLIS - NUDGE_AFTER_MILLIS);
    }, NUDGE_AFTER_MILLIS);
  }

  onHeardSpeech() {
    this.heardSpeechAt = Date.now();
    this.armSilenceTimers();
  }

  startListening() {
    // Synchronous, inside the tap: an AudioContext created after an await stays suspended.
    if (this.speechGate.prepare) this.speechGate.prepare();
    if (this.speechGate instanceof WebSpeechGate && !WebSpeechGate.available()) {
      this.micUnavailable = true;
      this.renderStatus();
      return;
    }
    this.isSessionActive = true;
    this.micDenied = false;
    this.renderStatus();
    this.startLevelMeter();
    this.maybeListen();
  }

  startLevelMeter() {
    if (this.levelTimer || typeof this.speechGate.isSpeaking !== "function") return;
    this.levelTimer = setInterval(() => {
      if (this.destroyed || !this.isSessionActive) return;
      // Sound, not words: a sibling or a TV can reset the ladder, and a very quiet mumble may
      // not. SILENCE_RMS in speech.js is the knob; log sampleLevel() in the real room to set it.
      if (this.speechGate.isSpeaking()) this.onHeardSpeech();
    }, 700);
  }

  maybeListen() {
    if (this.destroyed || !this.isSessionActive || this.isSpeaking || !this.currentWord()) return;
    const begin = () => {
      if (this.destroyed || !this.isSessionActive || this.isSpeaking) return;
      let heardThisSession = false;
      this.speechGate.startContinuous(
        detectSpeechLanguageCode(this.currentWord() ? this.currentWord().spoken : "", this.options.languageCode),
        {
          onPartial: (transcript) => {
            const heard = tokenizeTranscript(transcript);
            if (heard.length > 0) {
              this.liveTranscript = heard;
              this.onHeardSpeech();
            }
          },
          onSegment: (hypotheses) => {
            const settledTokens = hypotheses.length > 0 ? tokenizeTranscript(hypotheses[0]) : [];
            const settled = settledTokens.length > 0 ? settledTokens : this.liveTranscript;
            this.closedTranscript = [...this.closedTranscript, ...settled];
            this.liveTranscript = [];
            if (settled.length > 0) {
              heardThisSession = true;
              this.onHeardSpeech();
            }
            this.maybeUpdateReview();
          },
          onEnded: () => {
            if (this.liveTranscript.length > 0) {
              this.closedTranscript = [...this.closedTranscript, ...this.liveTranscript];
              this.liveTranscript = [];
            }
            this.wasInstantFailure = !heardThisSession;
            this.maybeUpdateReview();
            // Session closed (silence timeout, tab switch, error): reopen it.
            if (this.isSessionActive && !this.isSpeaking) this.maybeListen();
          },
          onUnavailable: () => {
            this.isSessionActive = false;
            this.micUnavailable = true;
            this.micDenied = true;
            this.renderStatus();
          }
        }
      );
    };
    if (this.wasInstantFailure) setTimeout(begin, SESSION_RESTART_DELAY_MILLIS);
    else begin();
  }

  stopAndReview() {
    // Stopped, not cancelled: stopping asks for the final transcript.
    this.speechGate.stop();
    this.isSessionActive = false;
    this.reviewTargets = [...this.visited];
    this.reviewRequested = true;
    this.review = null;
    this.renderStatus();
    this.computeReview();
  }

  checkFinished() {
    if (this.currentWord() !== null) return;
    if (this.checkBlocked) return;   // nothing was heard; an empty review panel is not news
    if (this.isSessionActive) {
      this.speechGate.stop();
      this.isSessionActive = false;
    }
    this.reviewTargets = [...this.visited];
    this.reviewRequested = true;
    this.computeReview();
  }

  maybeUpdateReview() {
    if (this.reviewRequested) this.computeReview();
  }

  computeReview() {
    const tokens = [...this.closedTranscript, ...this.liveTranscript];
    const targets = this.reviewTargets
      .map((i) => this.words[i] ? this.words[i].spoken : null)
      .filter((w) => w && [...w].some((ch) => /\p{L}/u.test(ch)));
    this.review = reviewReading(tokens, targets);
    this.render();
  }

  onAiStateChanged() { this.renderStepHelp(); }

  readShare() {
    if (this.review && this.review.length > 0) {
      return this.review.filter((r) => r.outcome === ReadOutcome.Correct).length / this.review.length;
    }
    return ladderMasteryShare(this.ladder);
  }

  /* ---- rendering ---- */

  render() {
    if (this.destroyed) return;
    this.host.replaceChildren();
    const share = Math.round(this.readShare() * 100);

    this.root = el("div", { class: "stack s-4" },
      el("div", { class: "title-md" }, t("focus_mode_title")),
      el("div", { class: "body-sm muted" }, t("focus_progress", share)),
      el("div", {
        class: "progress-track", role: "progressbar",
        "aria-valuenow": String(share), "aria-valuemin": "0", "aria-valuemax": "100"
      }, el("div", { class: "progress-fill", style: `width:${share}%` }))
    );

    this.textBlockHost = el("div");
    this.root.append(this.textBlockHost);
    this.renderTextBlock();

    this.statusHost = el("div", { class: "stack" });
    this.root.append(this.statusHost);
    this.renderStatus();

    this.stepHelpHost = el("div");
    this.root.append(this.stepHelpHost);
    this.renderStepHelp();

    this.host.append(this.root);
  }

  renderTextBlock() {
    if (!this.textBlockHost) return;
    this.textBlockHost.replaceChildren();
    const block = el("div", { class: "focus-block" });
    const emphasised = this.ladder.step !== FocusStep.Sweep || this.isFlashing;
    this.words.forEach((word, i) => {
      if (i > 0) block.append(" ");
      const isFocus = i === this.ladder.wordIndex && emphasised;
      block.append(el("span", {
        class: "fw" + (isFocus ? " focused" : ""),
        onclick: () => this.moveFocusTo(i)
      }, word.display));
    });
    this.textBlockHost.append(block);
    const focusNode = block.querySelector(".fw.focused");
    if (focusNode) {
      // Jump, don't glide: motion inside the reading area is a disorientation trigger.
      block.scrollTop = Math.max(0, focusNode.offsetTop - block.clientHeight / 3);
    }
  }

  renderStatus() {
    if (!this.statusHost || this.destroyed) return;
    this.statusHost.replaceChildren();

    if (this.currentWord() === null) {
      this.statusHost.append(el("div", { class: "body-lg" }, t("focus_finished")));
      if (this.reviewRequested) this.statusHost.append(this.renderReviewPanel());
      const practiseWords = [...new Set([...this.ladder.triggerWords, ...misreadWordsFrom(this.review)])];
      if (practiseWords.length > 0) {
        this.statusHost.append(el("button", {
          class: "btn full", onclick: () => this.options.onCollectTriggerWords(practiseWords)
        }, t("focus_practise_words")));
      }
      return;
    }

    const listening = this.isSessionActive && !this.isSpeaking;
    // Unchecked reading is a mode, not a failure. The headline says what the app WILL do, the
    // microphone button is absent rather than dead, and the reason sits quietly underneath.
    this.statusHost.append(
      this.checkBlocked
        ? el("div", { class: "row s-2" }, icon("book-open", 20),
            el("span", { class: "title-md" }, t("focus_check_off")))
        : el("div", { class: "row s-2 text-success" },
            icon(listening ? "mic" : "mic-off", 20),
            el("span", { class: "title-md" }, listening ? t("focus_listening") : t("focus_listen"))),
      // Controls right under the reading block; nothing variable-height above them.
      el("button", { class: "btn full", onclick: () => this.moveFocusTo(this.ladder.wordIndex + 1) },
        t("focus_next_word"))
    );

    if (!this.checkBlocked) {
      this.statusHost.append(el("button", {
        class: "btn tonal full",
        onclick: () => {
          if (this.isSessionActive) this.stopAndReview();
          else {
            this.reviewRequested = false;
            this.review = null;
            this.micUnavailable = false;
            this.startListening();
          }
        }
      }, icon(this.isSessionActive ? "stop" : "mic", 20),
        this.isSessionActive ? t("focus_check_now") : t("focus_listen_start")));
    }

    this.statusHost.append(
      el("button", { class: "btn tonal full", onclick: () => this.onHelp() },
        icon("lightbulb", 20), t("focus_help")),
      el("div", { class: "body-sm muted" }, t("focus_tap_word_hint"))
    );

    if (this.checkBlocked) {
      this.statusHost.append(el("div", { class: "row row--top s-2" }, icon("alert", 18),
        el("span", { class: "body-sm muted" }, t(CHECK_OFF_REASON[this.checkBlocked]))));
    } else if (this.micUnavailable || this.micDenied) {
      this.statusHost.append(el("div", { class: "row row--top s-2" }, icon("alert", 18),
        el("span", { class: "body-sm muted" },
          this.micDenied ? t("focus_mic_permission") : t("focus_mic_unavailable"))));
    }
    if (this.ttsSilent || this.voiceMissing) {
      this.statusHost.append(el("div", { class: "row row--top s-2 text-error" }, icon("alert", 18),
        el("span", { class: "body-sm" }, this.ttsSilent ? t("tts_silent") : t("tts_voice_missing"))));
    }


    if (this.reviewRequested) this.statusHost.append(this.renderReviewPanel());
    if (this.ladder.suggestPause) {
      this.statusHost.append(el("div", { class: "card card--quiet stack" },
        el("div", { class: "title-md" }, t("focus_pause_title")),
        el("button", {
          class: "btn self-start", onclick: () => { this.ladder = ladderOnPauseAcknowledged(this.ladder); this.renderStatus(); }
        }, t("focus_pause_continue"))
      ));
    }
  }

  renderStepHelp() {
    if (!this.stepHelpHost || this.destroyed) return;
    this.stepHelpHost.replaceChildren();
    const word = this.currentWord();
    if (!word) return;
    if (this.ladder.step === FocusStep.Letters) {
      this.stepHelpHost.append(el("div", { class: "stack s-2" },
        el("div", { class: "body-sm muted" }, t("focus_step_letters")),
        el("div", { class: "title-md" },
          letterNamesFor(word.spoken, detectSpeechLanguageCode(word.spoken, this.options.languageCode)).join(" · "))
      ));
    } else if (this.ladder.step === FocusStep.Meaning) {
      const aiState = this.options.getAiState();
      const inner = el("div", { class: "card card--quiet stack s-2" },
        el("div", { class: "body-sm muted" }, t("focus_step_meaning")));
      if (aiState.kind === "loading") inner.append(el("div", { class: "spinner sm" }));
      else if (aiState.kind === "success") {
        inner.append(
          el("div", { class: "prose" }, aiState.result),
          el("button", { class: "text-btn self-start", onclick: () => this.options.onDismissHint() }, t("focus_hint_dismiss"))
        );
      } else if (aiState.kind === "error") {
        inner.append(
          el("div", { class: "body-md text-error" }, t("reader_ai_error")),
          el("button", { class: "text-btn self-start", onclick: () => this.options.onDismissHint() }, t("focus_hint_dismiss"))
        );
      }
      this.stepHelpHost.append(inner);
    }
  }

  // Only the words that did not come out right are listed.
  renderReviewPanel() {
    const panel = el("div", { class: "card card--quiet stack s-2" },
      el("div", { class: "title-md" }, t("focus_review_title")));
    if (this.review === null) {
      panel.append(el("div", { class: "spinner sm" }));
    } else if (this.review.length === 0) {
      panel.append(el("div", { class: "body-md" }, t("focus_review_nothing_heard")));
    } else {
      const mistakes = mistakesFrom(this.review);
      if (mistakes.length === 0) {
        panel.append(el("div", { class: "body-md" }, t("focus_review_clean")));
      } else {
        for (const entry of mistakes) {
          panel.append(el("div", { class: "stack s-1" },
            el("div", { class: "title-md text-error" }, entry.word),
            el("div", { class: "body-sm muted" },
              entry.heard === null ? t("focus_review_not_heard") : t("focus_review_heard", entry.heard))
          ));
        }
      }
    }
    return panel;
  }
}

/* =========================================================================
   TRAINING TAB — TrainingScreen.kt + TrainingViewModel.kt
   ========================================================================= */

// Each skill carries an icon as well as a colour. Requiring AA on light paper caps the four
// skill colours in a narrow lightness band, and red-green colour blindness collapses what is
// left, so colour alone cannot tell a reader which exercise they are on.
const SKILL_ICONS = {
  phonology: "skill-ear",
  decoding: "skill-letter",
  visual: "skill-eye",
  morphology: "skill-parts"
};

function exerciseInsight(exercise) {
  switch (exercise.type) {
    case "auditory_match":
      return { skill: "phonology", label: "training_skill_phonology", goal: "training_goal_phonology", instruction: "training_instruction_auditory_match" };
    case "word_recognition":
      return { skill: "visual", label: "training_skill_visual", goal: "training_goal_visual", instruction: "training_instruction_word_recognition" };
    case "root_suffix_identification":
    case "word_segmentation":
      return { skill: "morphology", label: "training_skill_morphology", goal: "training_goal_morphology", instruction: "training_instruction_morphology" };
    default:
      return { skill: "decoding", label: "training_skill_decoding", goal: "training_goal_decoding", instruction: "training_instruction_choice" };
  }
}

const trainingScreen = {
  container: null,
  session: null,        // {exercises, currentIndex, selectedAnswer, feedback..., startedAt}
  loading: false,
  errorKey: null,
  pendingSyncCount: storage.pendingAttempts.length,

  reset() {
    this.session = null;
    this.loading = false;
    this.errorKey = null;
    this.render();
  },

  speak(text) {
    ttsSpeak(text, { languageCode: detectSpeechLanguageCode(text, S.languageCode), rate: 0.9 });
  },

  async start() {
    if (!S.user) return;
    this.loading = true; this.errorKey = null;
    this.render();
    try {
      const exercises = await generateExercises(S.user.userId, S.trainingSourceWords, 6, S.languageCode);
      this.session = {
        exercises, currentIndex: 0, selectedAnswer: "",
        feedbackKey: null, feedbackAnswer: null, lastAnswerCorrect: null,
        isSubmitting: false, startedAt: performance.now()
      };
    } catch {
      this.errorKey = "error_training_load";
    }
    this.loading = false;
    this.render();
  },

  async submit() {
    const s = this.session;
    if (!s || !s.selectedAnswer || s.feedbackKey || s.isSubmitting) return;
    const exercise = s.exercises[s.currentIndex];
    s.isSubmitting = true;
    this.render();
    const elapsed = Math.round(performance.now() - s.startedAt);
    try {
      const result = await submitExerciseAttempt(S.user.userId, exercise, s.selectedAnswer, elapsed);
      s.feedbackKey = result.isCorrect ? "training_feedback_correct" : "training_feedback_incorrect";
      s.feedbackAnswer = result.isCorrect ? null : exercise.correctAnswer;
      s.lastAnswerCorrect = result.isCorrect;
    } catch {
      s.feedbackKey = "training_attempt_save_error";
      s.feedbackAnswer = null;
      s.lastAnswerCorrect = null;
    }
    s.isSubmitting = false;
    this.render();
  },

  next() {
    const s = this.session;
    if (!s) return;
    s.currentIndex += 1;
    s.selectedAnswer = "";
    s.feedbackKey = null; s.feedbackAnswer = null; s.lastAnswerCorrect = null;
    s.startedAt = performance.now();
    this.render();
  },

  render() {
    const c = this.container;
    if (!c) return;
    c.replaceChildren();

    const header = el("div", { class: "stack s-1" },
      el("h2", { class: "headline" }, t("training_title")),
      el("div", { class: "body-md muted" }, t("training_subtitle")),
      el("div", { class: "body-sm muted" },
        S.trainingSourceWords.length === 0 ? t("training_source_empty") : t("training_source_ready", S.trainingSourceWords.length))
    );
    const root = el("div", { class: "stack s-4" }, header);
    c.append(root);

    if (S.userLoading) { root.append(renderLoadingState(t("training_profile_loading"))); return; }
    if (S.userError || !S.user) {
      root.append(renderErrorState(t("error_profile_create"), () => loadUser()));
      return;
    }
    if (this.loading) { root.append(renderLoadingState(t("training_loading"))); return; }
    if (this.errorKey) { root.append(renderErrorState(t(this.errorKey), () => this.start())); return; }

    const s = this.session;
    if (!s || s.exercises.length === 0) { root.append(this.renderStartPanel()); return; }
    if (s.currentIndex >= s.exercises.length) { root.append(this.renderCompletePanel()); return; }
    root.append(this.renderExercise(s));
  },

  renderSkillRail(activeSkill) {
    const steps = [
      ["phonology", "training_skill_phonology"], ["decoding", "training_skill_decoding"],
      ["visual", "training_skill_visual"], ["morphology", "training_skill_morphology"]
    ];
    return el("div", { class: "row wrap s-2" },
      steps.map(([skill, labelKey]) =>
        el("span", { class: "chip" + (activeSkill === skill ? " active" : ""), "data-skill": skill },
          icon(SKILL_ICONS[skill], 18), t(labelKey))
      )
    );
  },

  renderStartPanel() {
    return el("div", { class: "card stack" },
      el("h3", { class: "title-lg" }, t("training_intro_title")),
      el("div", { class: "body-lg muted" }, t("training_intro")),
      this.renderSkillRail(null),
      el("button", { class: "btn full", onclick: () => this.start() },
        S.trainingSourceWords.length === 0 ? t("training_start_practice") : t("training_start"))
    );
  },

  renderCompletePanel() {
    return el("div", { class: "card stack" },
      el("div", { class: "text-success" }, icon("check", 36)),
      el("h3", { class: "title-lg" }, t("training_complete")),
      el("div", { class: "body-lg muted" }, t("training_complete_body")),
      el("button", { class: "btn full", onclick: () => this.start() }, t("training_again"))
    );
  },

  renderExercise(s) {
    const exercise = s.exercises[s.currentIndex];
    const insight = exerciseInsight(exercise);
    const progress = ((s.currentIndex + 1) / s.exercises.length) * 100;
    const hasPending = this.pendingSyncCount > 0;

    // data-skill resolves --skill and --skill-tint for everything inside the card, so no colour
    // is interpolated in JS and nothing mixes toward white.
    const card = el("div", { class: "card stack", "data-skill": insight.skill },
      el("div", { class: "caption tabular" }, t("training_step_label", s.currentIndex + 1, s.exercises.length)),
      el("div", {
        class: "progress-track", role: "progressbar",
        "aria-valuenow": String(Math.round(progress)), "aria-valuemin": "0", "aria-valuemax": "100"
      }, el("div", { class: "progress-fill", style: `width:${progress}%` })),
      el("span", { class: "chip active self-start" }, icon(SKILL_ICONS[insight.skill], 18), t(insight.label)),
      el("div", { class: "title-md" }, exercise.prompt || t(insight.instruction)),
      el("div", { class: "body-md muted" }, t(insight.instruction))
    );

    // Exercise body per type. Auditory keeps the target word hidden — the answer IS the word.
    const showWord = exercise.type !== "auditory_match" && exercise.type !== "word_recognition" &&
      exercise.targetWord.trim() !== "";
    if (showWord) {
      card.append(el("div", { class: "word-lg" }, exercise.targetWord));
    }
    if (exercise.targetWord.trim() !== "") {
      // Tonal, not outlined: this is an action, and an outlined button directly above the
      // outlined answer rows reads as a fifth option.
      card.append(el("button", {
        class: "btn tonal full", onclick: () => this.speak(exercise.targetWord)
      }, icon("volume", 20), t("training_listen")));
    }

    card.append(el("div", { class: "stack s-2" },
      exercise.options.map((option) =>
        el("button", {
          class: "btn outlined answer full" + (s.selectedAnswer === option ? " selected" : ""),
          onclick: () => {
            if (s.feedbackKey) return;
            s.selectedAnswer = option;
            this.render();
          }
        }, option)
      )
    ));

    if (s.feedbackKey) {
      const text = s.feedbackKey === "training_feedback_incorrect"
        ? t("training_feedback_incorrect", s.feedbackAnswer || exercise.correctAnswer)
        : t(s.feedbackKey);
      const detailKey = s.lastAnswerCorrect === true ? "training_feedback_correct_detail" :
        s.lastAnswerCorrect === false ? "training_feedback_incorrect_detail" : "training_feedback_saved_detail";
      // Right and wrong differ by icon, left rule and hue — the two tints alone are ~1:1 apart.
      const modifier = s.lastAnswerCorrect === true ? " is-correct" :
        s.lastAnswerCorrect === false ? " is-wrong" : "";
      const mark = s.lastAnswerCorrect === true ? "check" :
        s.lastAnswerCorrect === false ? "refresh" : "cloud-up";
      card.append(el("div", { class: "feedback-box" + modifier },
        icon(mark, 20),
        el("div", { class: "stack s-1 grow" },
          el("div", { class: "feedback-title" }, text),
          el("div", { class: "body-md muted" }, t(detailKey))
        )
      ));
      card.append(el("button", { class: "btn full", onclick: () => this.next() }, t("training_next_word")));
    } else {
      card.append(el("button", {
        class: "btn full",
        disabled: (!s.selectedAnswer || s.isSubmitting) ? "" : undefined,
        onclick: () => this.submit()
      }, t("training_submit")));
    }

    // Sync state only when there is something pending: a permanent "all synced" row is chrome.
    const syncNote = hasPending
      ? el("div", { class: "row s-2 muted" }, icon("cloud-up", 18),
          el("span", { class: "body-sm" }, t("training_sync_pending", this.pendingSyncCount)))
      : null;

    return el("div", { class: "stack s-4" }, this.renderSkillRail(insight.skill), card, syncNote);
  }
};

onPendingCountChange((count) => {
  trainingScreen.pendingSyncCount = count;
  if (S.tab === "training") trainingScreen.render();
});

function renderLoadingState(message) {
  return el("div", { class: "stack s-3 center items-center" },
    el("div", { class: "spinner" }),
    el("div", { class: "body-lg center" }, message)
  );
}
function renderErrorState(message, onRetry) {
  return el("div", { class: "stack s-3" },
    el("div", { class: "body-lg center" }, message),
    onRetry ? el("button", { class: "btn full", onclick: onRetry }, icon("refresh", 20), t("try_again")) : null
  );
}

/* =========================================================================
   PROGRESS TAB — ProgressScreen.kt
   ========================================================================= */

const progressScreen = {
  container: null,
  state: { kind: "idle" },

  onShow() {
    if (S.user && this.state.kind === "idle") this.load();
    else this.render();
  },

  async load() {
    if (!S.user) { this.render(); return; }
    this.state = { kind: "loading" };
    this.render();
    try {
      this.state = { kind: "success", data: await getProgress(S.user.userId) };
    } catch {
      this.state = { kind: "error" };
    }
    this.render();
  },

  render() {
    const c = this.container;
    if (!c) return;
    c.replaceChildren();
    const root = el("div", { class: "stack s-4" },
      el("h2", { class: "headline" }, t("progress_title")));
    c.append(root);

    if (!S.user) { root.append(renderLoadingState(t("progress_profile_loading"))); return; }
    if (this.state.kind === "loading" || this.state.kind === "idle") {
      root.append(renderLoadingState(t("progress_loading")));
      return;
    }
    if (this.state.kind === "error") {
      root.append(renderErrorState(t("error_progress_load"), () => this.load()));
      return;
    }

    const progress = this.state.data;
    if (progress.totalExercises === 0) {
      root.append(el("div", { class: "card" }, el("div", { class: "body-lg" }, t("progress_empty"))));
    }
    const metric = (label, value) =>
      el("div", { class: "metric" },
        el("div", { class: "caption" }, label),
        el("div", { class: "metric-value" }, value)
      );
    root.append(el("div", { class: "metric-grid" },
      metric(t("progress_total_exercises"), String(progress.totalExercises)),
      metric(t("progress_accuracy"), Math.round(progress.exerciseAccuracy * 100) + "%"),
      metric(t("progress_average_response"), progress.averageResponseTimeMs + " ms"),
      metric(t("progress_current_difficulty"), String(progress.skillProfile.currentDifficulty)),
      metric(t("progress_phonological_skill"), Math.round(progress.skillProfile.phonologicalSkill * 100) + "%"),
      metric(t("progress_decoding_skill"), Math.round(progress.skillProfile.decodingFluency * 100) + "%")
    ));
    root.append(el("button", { class: "btn self-start", onclick: () => this.load() },
      icon("refresh", 20), t("progress_refresh")));
  }
};

/* =========================================================================
   SETTINGS TAB — ProfileScreen.kt + MainViewModel auth
   ========================================================================= */

const settingsScreen = {
  container: null,
  authMode: "login",
  showAdvanced: false,
  form: { username: "", password: "", displayName: "" },

  async changeLanguage(code) {
    const normalized = normalizeLangCode(code);
    S.languageCode = normalized;
    storage.languageCode = normalized;
    document.documentElement.lang = normalized;
    if (S.user) {
      try { await updateUserLanguage(S.user.userId, normalized); } catch { /* offline is fine */ }
      try { S.user = await ensureUser(); } catch { /* keep the old profile */ }
    }
    renderNav();
    readerScreen.destroyReaders();
    readerScreen.render();
    trainingScreen.render();
    progressScreen.render();
    this.render();
  },

  async doAuth() {
    const { username, password, displayName } = this.form;
    if (username.trim().length < 3 || password.length < 4) return;
    const isLogin = this.authMode === "login";
    S.authFeedback = { key: isLogin ? "account_login_loading" : "account_register_loading", isError: false };
    S.userLoading = true;
    this.render();
    try {
      S.user = isLogin
        ? await loginUser(username.trim(), password)
        : await registerUser(username.trim(), password, displayName.trim());
      if (isLogin) {
        S.languageCode = normalizeLangCode(S.user.languagePreference);
        storage.languageCode = S.languageCode;
        renderNav();
      }
      S.authFeedback = { key: isLogin ? "account_login_success" : "account_register_success", isError: false };
      S.userError = false;
    } catch {
      S.authFeedback = { key: isLogin ? "account_login_error" : "account_register_error", isError: true };
    }
    S.userLoading = false;
    this.render();
    trainingScreen.render();
  },

  render() {
    const c = this.container;
    if (!c) return;
    c.replaceChildren();
    const user = S.user;

    // Language and profile are one card: the profile's only fact beyond the name is which
    // language is selected, which the radios directly above already say.
    const profileCard = el("div", { class: "card stack s-1" },
      el("div", { class: "title-md" }, t("settings_language_title")),
      APP_LANGUAGES.map((lang) => {
        const radio = el("input", { type: "radio", name: "app-language" });
        radio.checked = S.languageCode === lang.code;
        radio.addEventListener("change", () => this.changeLanguage(lang.code));
        return el("label", { class: "radio-row" }, radio, t(lang.labelKey));
      }),
      el("hr", { class: "hairline-rule" }),
      el("div", { class: "title-md" }, t("settings_theme_title")),
      [["system", "settings_theme_system"], ["light", "settings_theme_light"], ["dark", "settings_theme_dark"]]
        .map(([value, labelKey]) => {
          const radio = el("input", { type: "radio", name: "app-theme" });
          radio.checked = (storage.theme || "system") === value;
          radio.addEventListener("change", () => applyTheme(value));
          return el("label", { class: "radio-row" }, radio, t(labelKey));
        }),
      el("hr", { class: "hairline-rule" }),
      el("div", { class: "body-md" }, t("profile_student", user ? user.displayName : "...")),
      user && user.username ? el("div", { class: "body-md" }, t("profile_username", user.username)) : null,
      el("button", { class: "text-btn self-start", onclick: () => { this.showAdvanced = !this.showAdvanced; this.render(); } },
        this.showAdvanced ? t("settings_hide_advanced") : t("settings_advanced")),
      this.showAdvanced ? el("div", { class: "body-sm muted" }, t("profile_user_id", user ? user.userId : "...")) : null
    );

    const isLogin = this.authMode === "login";
    const field = (labelKey, key, type = "text") => {
      const input = el("input", { class: "field", type, value: this.form[key] });
      input.addEventListener("input", () => { this.form[key] = input.value; refreshSubmit(); });
      return el("div", null, el("label", { class: "field-label" }, t(labelKey)), input);
    };
    const submitBtn = el("button", { class: "btn lg full", onclick: () => this.doAuth() },
      S.userLoading ? el("div", { class: "spinner sm on-accent" }) : null,
      isLogin ? t("account_login") : t("account_register")
    );
    const refreshSubmit = () => {
      const ok = this.form.username.trim().length >= 3 && this.form.password.length >= 4 && !S.userLoading;
      submitBtn.disabled = !ok;
    };

    const feedback = S.authFeedback || (S.userError ? { key: "error_account_auth", isError: true } : null);
    const accountCard = el("div", { class: "card stack" },
      el("div", { class: "title-md" }, t("account_title")),
      feedback ? el("div", { class: "feedback-banner " + (feedback.isError ? "error" : "ok") }, t(feedback.key)) : null,
      el("div", { class: "body-md muted" }, isLogin ? t("account_login_hint") : t("account_register_hint")),
      field("account_username", "username"),
      field("account_password", "password", "password"),
      isLogin ? null : field("account_display_name", "displayName"),
      submitBtn,
      el("div", { class: "row row--center" },
        el("span", { class: "body-md muted" }, isLogin ? t("account_no_account") : t("account_has_account")),
        el("button", { class: "text-btn", onclick: () => { this.authMode = isLogin ? "register" : "login"; this.render(); } },
          isLogin ? t("account_register_link") : t("account_login_link"))
      )
    );
    refreshSubmit();

    c.append(el("div", { class: "stack s-4" },
      el("h2", { class: "headline" }, t("settings_title")),
      profileCard, accountCard
    ));
  }
};

/* ---------------- bottom navigation + boot ---------------- */

/* ---------------- theme ---------------- */
// index.html stamps data-theme before first paint; this keeps it in sync afterwards.
// Individual surface preference varies enormously among dyslexic readers — a warm paper is as
// contested as a dark one — so the phone's setting is the default, not the verdict.
const THEME_PAGE = { light: "#D4D7DB", dark: "#202124" };

function resolvedTheme(preference) {
  if (preference === "light" || preference === "dark") return preference;
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function applyTheme(preference) {
  storage.theme = preference;
  const theme = resolvedTheme(preference);
  document.documentElement.dataset.theme = theme;
  // iOS reads apple-mobile-web-app-status-bar-style at launch, so this meta follows the theme
  // in Safari only. It is still the only knob there is, and it is correct there.
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", THEME_PAGE[theme]);
}

window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
  if ((storage.theme || "system") === "system") applyTheme("system");
});

function renderNav() {
  const nav = document.querySelector("nav.bottom .inner");
  nav.replaceChildren();
  const tabs = [
    ["reader", "book-open", "nav_reader"], ["training", "dumbbell", "nav_training"],
    ["progress", "chart", "nav_progress"], ["settings", "settings", "nav_settings"]
  ];
  for (const [tab, iconName, labelKey] of tabs) {
    nav.append(el("button", { "data-tab": tab, class: S.tab === tab ? "active" : "", onclick: () => switchTab(tab) },
      icon(iconName, 22),
      el("span", null, t(labelKey))
    ));
  }
}

function boot() {
  applyTheme(storage.theme || "system");
  document.documentElement.lang = S.languageCode;
  const screen = document.getElementById("screen");
  for (const name of ["reader", "training", "progress", "settings"]) {
    const container = el("div", { class: name === "reader" ? "" : "hidden" });
    tabContainers[name] = container;
    screen.append(container);
  }
  readerScreen.container = tabContainers.reader;
  trainingScreen.container = tabContainers.training;
  progressScreen.container = tabContainers.progress;
  settingsScreen.container = tabContainers.settings;

  renderNav();
  readerScreen.render();
  trainingScreen.render();
  settingsScreen.render();
  loadUser();
  syncPendingAttempts();
}

document.addEventListener("DOMContentLoaded", boot);
