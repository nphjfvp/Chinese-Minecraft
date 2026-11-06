"""Speech recognition with wav2vec2 CTC acoustic models, one per language.

CTC models map sound to characters frame by frame without a language model, so they do not
"repair" what was said into a plausible sentence; a mispronounced syllable comes out as a
different character (or nothing). Chinese is then compared syllable by syllable in pinyin,
languages written in Latin letters word by word.
"""

from __future__ import annotations

import os
import re
import threading
import unicodedata
from dataclasses import dataclass

import numpy as np
import torch

# One model at a time on the GPU: loading two in parallel threads, or recognising while another loads, crashes MPS.
TORCH_LOCK = threading.Lock()
from pypinyin import Style, lazy_pinyin
# a model published only as .bin makes the library fetch a converted 1.26 GB safetensors copy in the background,
# doubling every language's download; the .bin is all we load
os.environ.setdefault("DISABLE_SAFETENSORS_CONVERSION", "1")
from transformers import Wav2Vec2ForCTC, Wav2Vec2Processor  # noqa: E402

# kehanlu/mandarin-wav2vec2-aishell1 ships a differently shaped CTC head and comes out garbled
# through Wav2Vec2ForCTC; these XLSR fine-tunes are plain character-level CTC models.
MODELS = {
    "zh": "jonatasgrosman/wav2vec2-large-xlsr-53-chinese-zh-cn",
    "es": "jonatasgrosman/wav2vec2-large-xlsr-53-spanish",
    "pt": "jonatasgrosman/wav2vec2-large-xlsr-53-portuguese",
    "ja": "jonatasgrosman/wav2vec2-large-xlsr-53-japanese",
    "de": "jonatasgrosman/wav2vec2-large-xlsr-53-german",
    "en": "jonatasgrosman/wav2vec2-large-xlsr-53-english",
}
MODEL_ID = MODELS["zh"]
SAMPLE_RATE = 16_000
SILENCE_RMS = 0.002  # quieter than this is an unplugged or unpermitted microphone, not speech
# Every take is padded up to one of these lengths (seconds), so the model only ever sees a handful of input
# shapes: the first pass at a new shape costs seconds of kernel set-up, a repeated one ~0.3 s.
BUCKETS = [1.0 + 0.5 * i for i in range(15)]  # 1.0 .. 8.0 s
PAD_S = 0.4  # CTC models clip the first syllable when speech starts at sample 0: give them some room


def pick_device() -> str:
    """Apple's GPU (MPS) halves the time of a take; ZH_HELPER_DEVICE=cpu forces the CPU."""
    import os

    wanted = os.environ.get("ZH_HELPER_DEVICE", "").lower()
    if wanted:
        return wanted
    return "mps" if torch.backends.mps.is_available() else "cpu"


@dataclass
class Result:
    text: str
    reading: list[str]  # Chinese: pinyin with tone marks, one entry per syllable; other languages: the words heard
    confidence: float  # mean max-softmax over emitted frames, 0..1
    rms: float = 0.0  # loudness of the recording; ~0 means the microphone gave us nothing

    @property
    def pinyin(self) -> list[str]:
        return self.reading


class Recognizer:
    def __init__(self, language: str = "zh", model_id: str | None = None) -> None:
        self.language = language
        self.model_id = model_id or MODELS.get(language, MODEL_ID)
        # a few threads are plenty for one short clip; grabbing every core fights with the game and gets slower
        torch.set_num_threads(min(4, max(1, (torch.get_num_threads() or 4))))
        self.processor = Wav2Vec2Processor.from_pretrained(self.model_id)
        with TORCH_LOCK:
            # the .bin only (see DISABLE_SAFETENSORS_CONVERSION above): one 1.26 GB file per language, not two
            self.model = Wav2Vec2ForCTC.from_pretrained(self.model_id, use_safetensors=False).eval()
            self.device = pick_device()
            try:
                self.model.to(self.device)
            except Exception as e:  # noqa: BLE001 - an odd GPU: stay on the CPU
                print(f"{self.device} is not usable ({e}); using the CPU", flush=True)
                self.device = "cpu"
                self.model.to("cpu")

    def warm_up(self) -> None:
        """One pass per input shape, so the first real take is as fast as the rest."""
        rng = np.random.default_rng(0)
        for seconds in BUCKETS:
            n = int(seconds * SAMPLE_RATE) - 2 * int(PAD_S * SAMPLE_RATE)
            self.recognize((rng.standard_normal(n) * 0.05).astype(np.float32))

    def recognize(self, audio: np.ndarray, sample_rate: int = SAMPLE_RATE) -> Result:
        with TORCH_LOCK:
            return self._recognize(audio, sample_rate)

    @torch.inference_mode()
    def _recognize(self, audio: np.ndarray, sample_rate: int = SAMPLE_RATE) -> Result:
        if sample_rate != SAMPLE_RATE:
            audio = resample(audio, sample_rate, SAMPLE_RATE)
        audio = audio.astype(np.float32)
        if audio.size == 0:
            return Result("", [], 0.0)
        rms = float(np.sqrt(np.mean(audio * audio)))
        if rms < SILENCE_RMS:
            return Result("", [], 0.0, rms)
        # quiet microphones: bring the peak up to -1 dBFS (at most 20x, so pure noise is not amplified wildly)
        peak = float(np.max(np.abs(audio)))
        if peak > 0:
            audio = audio * min(0.9 / peak, 20.0)
        pad = np.zeros(int(PAD_S * SAMPLE_RATE), dtype=np.float32)
        audio = np.concatenate([pad, audio, pad])
        # pad with silence up to the next bucket length (see BUCKETS); overlong takes are cut to the last bucket
        target = int(next((b for b in BUCKETS if b * SAMPLE_RATE >= len(audio)), BUCKETS[-1]) * SAMPLE_RATE)
        audio = audio[:target] if len(audio) > target else np.concatenate([audio, np.zeros(target - len(audio), dtype=np.float32)])
        inputs = self.processor(audio, sampling_rate=SAMPLE_RATE, return_tensors="pt")
        inputs = {k: v.to(self.device) for k, v in inputs.items()}
        logits = self.model(**inputs).logits[0].float().cpu()
        probs = torch.softmax(logits, dim=-1)
        ids = torch.argmax(logits, dim=-1)
        blank = self.processor.tokenizer.pad_token_id
        emitted = probs[ids != blank].max(dim=-1).values
        confidence = float(emitted.mean()) if emitted.numel() else 0.0
        text = self.processor.decode(ids)
        if self.language == "zh":
            text = re.sub(r"[\s\[\]<>|]", "", text)  # drop separators / special tokens
            return Result(text, lazy_pinyin(text, style=Style.TONE), confidence, rms)
        if self.language == "ja":
            text = re.sub(r"[\s\[\]<>|]", "", text)
            return Result(text, moras(text), confidence, rms)
        text = re.sub(r"[\[\]<>|]", "", text).strip().lower()
        return Result(text, words_of(text), confidence, rms)

    def compare(self, expected: str, heard: Result, threshold: float) -> dict:
        if self.language == "zh":
            return compare(expected, heard.reading, threshold)
        if self.language == "ja":
            return compare_moras(expected, heard.reading, threshold)
        return compare_words(expected, heard.text, threshold)


def resample(audio: np.ndarray, src: int, dst: int) -> np.ndarray:
    """Linear resampling, good enough for speech from 44.1/48 kHz down to 16 kHz."""
    n = int(round(len(audio) * dst / src))
    x_old = np.linspace(0.0, 1.0, num=len(audio), endpoint=False)
    x_new = np.linspace(0.0, 1.0, num=n, endpoint=False)
    return np.interp(x_new, x_old, audio).astype(np.float32)


INITIALS = ("zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w")


# standalone spellings: wo is the final uo without an initial, yan is ian, you is iou...
STANDALONE = {
    "wu": "u", "wo": "uo", "wa": "ua", "wai": "uai", "wan": "uan", "wang": "uang", "wei": "uei", "wen": "uen", "weng": "ueng",
    "yi": "i", "ya": "ia", "yan": "ian", "yang": "iang", "yao": "iao", "ye": "ie", "yin": "in", "ying": "ing", "yong": "iong",
    "you": "iou", "yu": "ü", "yuan": "üan", "yue": "üe", "yun": "ün",
}


def split_syllable(s: str) -> tuple[str, str]:
    if s in STANDALONE:
        return "", STANDALONE[s]
    for ini in INITIALS:
        if s.startswith(ini):
            final = s[len(ini):]
            # ui/un/iu are written short after an initial; spell them out so gui ~ wei, liu ~ you
            final = {"ui": "uei", "un": "uen", "iu": "iou"}.get(final, final)
            return ini, final
    return "", s


def syllable_similarity(a: str, b: str) -> float:
    """1 = same syllable, 0.5 = same initial or same final (tǔ/kǔ, wā/wāi), 0 = different."""
    if a == b:
        return 1.0
    ia, fa = split_syllable(a)
    ib, fb = split_syllable(b)
    return 0.5 if (ia == ib and ia) or fa == fb else 0.0


def align_score(expected: list[str], heard: list[str]) -> tuple[float, list[float]]:
    """Best monotonic alignment by total similarity; returns (sum, per-expected-syllable similarity)."""
    n, m = len(expected), len(heard)
    best = [[0.0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            best[i][j] = max(best[i - 1][j], best[i][j - 1],
                             best[i - 1][j - 1] + syllable_similarity(expected[i - 1], heard[j - 1]))
    # trace back to see how well each expected syllable was served
    per = [0.0] * n
    i, j = n, m
    while i > 0 and j > 0:
        sim = syllable_similarity(expected[i - 1], heard[j - 1])
        if best[i][j] == best[i - 1][j - 1] + sim and sim > 0:
            per[i - 1] = sim
            i, j = i - 1, j - 1
        elif best[i][j] == best[i - 1][j]:
            i -= 1
        else:
            j -= 1
    return best[n][m], per


def compare(expected_zh: str, heard_pinyin: list[str], threshold: float = 0.6) -> dict:
    """Syllable-level verdict.

    exact  - every syllable right, tones too
    pass   - similarity score >= threshold AND the last syllable (the object being learned) is at
             least half right: 歪泥苦 passes for 挖泥土, 我打牛 never passes for 我打猪
    wrong  - everything else
    """
    expected = lazy_pinyin(re.sub(r"[。！？，、]", "", expected_zh), style=Style.TONE)
    exp_plain = [strip_tones(s) for s in expected]
    got_plain = [strip_tones(s) for s in heard_pinyin]
    n = max(1, len(expected))
    total, per = align_score(exp_plain, got_plain)
    extra = max(0, len(got_plain) - len(exp_plain))  # rambling costs a little
    score = max(0.0, (total - 0.25 * extra) / n)
    tail_ok = per[-1] >= 0.5 if per else False
    tone_errors = sum(1 for e, g in zip(expected, heard_pinyin) if strip_tones(e) == strip_tones(g) and e != g)
    if expected == heard_pinyin:
        verdict = "exact"
    elif score >= threshold and tail_ok:
        verdict = "pass"
    else:
        verdict = "wrong"
    return {
        "expected": expected,
        "heard": heard_pinyin,
        "score": round(score, 3),
        "per_syllable": per,
        "tail_ok": tail_ok,
        "tone_errors": tone_errors,
        "syllable_errors": sum(1 for s in per if s < 1.0),
        "verdict": verdict,
    }


TONE_MAP = str.maketrans("āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ", "aaaaeeeeiiiioooouuuuüüüü")


def strip_tones(syllable: str) -> str:
    return syllable.translate(TONE_MAP)


def edit_distance(a: list[str], b: list[str]) -> int:
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


# ---------------------------------------------------------------- languages written in words (Spanish, ...)
def normalize_word(w: str) -> str:
    """Lower case, no accents (except ñ), no punctuation: 'Recogí' -> 'recogi'."""
    w = w.lower().replace("ñ", "\u0001")
    w = "".join(c for c in unicodedata.normalize("NFD", w) if unicodedata.category(c) != "Mn")
    w = w.replace("\u0001", "ñ")
    return re.sub(r"[^a-zñ0-9']", "", w)


def words_of(text: str) -> list[str]:
    return [n for n in (normalize_word(w) for w in text.split()) if n]


def word_similarity(a: str, b: str) -> float:
    """1 = same word, in between = close (one or two letters off), 0 = different."""
    if a == b:
        return 1.0
    d = edit_distance(list(a), list(b))
    sim = 1.0 - d / max(len(a), len(b), 1)
    return sim if sim >= 0.5 else 0.0


def align_words(expected: list[str], heard: list[str]) -> tuple[float, list[float]]:
    n, m = len(expected), len(heard)
    best = [[0.0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            best[i][j] = max(best[i - 1][j], best[i][j - 1], best[i - 1][j - 1] + word_similarity(expected[i - 1], heard[j - 1]))
    per = [0.0] * n
    i, j = n, m
    while i > 0 and j > 0:
        sim = word_similarity(expected[i - 1], heard[j - 1])
        if best[i][j] == best[i - 1][j - 1] + sim and sim > 0:
            per[i - 1] = sim
            i, j = i - 1, j - 1
        elif best[i][j] == best[i - 1][j]:
            i -= 1
        else:
            j -= 1
    return best[n][m], per


# ---------------------------------------------------------------- Japanese: kana syllables (moras)
# The Japanese model writes kanji and kana as it hears them (木 or き), so both sides are turned into kana first
# (pykakasi) and then into romaji syllables, which the word aligner above can score letter by letter: ka ~ ga, shi ~ chi.
KANA_ROMAJI = {
    "あ": "a", "い": "i", "う": "u", "え": "e", "お": "o", "か": "ka", "き": "ki", "く": "ku", "け": "ke", "こ": "ko",
    "さ": "sa", "し": "shi", "す": "su", "せ": "se", "そ": "so", "た": "ta", "ち": "chi", "つ": "tsu", "て": "te", "と": "to",
    "な": "na", "に": "ni", "ぬ": "nu", "ね": "ne", "の": "no", "は": "ha", "ひ": "hi", "ふ": "fu", "へ": "he", "ほ": "ho",
    "ま": "ma", "み": "mi", "む": "mu", "め": "me", "も": "mo", "や": "ya", "ゆ": "yu", "よ": "yo",
    "ら": "ra", "り": "ri", "る": "ru", "れ": "re", "ろ": "ro", "わ": "wa", "ゐ": "i", "ゑ": "e", "を": "o", "ん": "n",
    "が": "ga", "ぎ": "gi", "ぐ": "gu", "げ": "ge", "ご": "go", "ざ": "za", "じ": "ji", "ず": "zu", "ぜ": "ze", "ぞ": "zo",
    "だ": "da", "ぢ": "ji", "づ": "zu", "で": "de", "ど": "do", "ば": "ba", "び": "bi", "ぶ": "bu", "べ": "be", "ぼ": "bo",
    "ぱ": "pa", "ぴ": "pi", "ぷ": "pu", "ぺ": "pe", "ぽ": "po", "ぁ": "a", "ぃ": "i", "ぅ": "u", "ぇ": "e", "ぉ": "o",
    "ゔ": "vu", "っ": "q",
}
KANA_COMBOS = {
    "きゃ": "kya", "きゅ": "kyu", "きょ": "kyo", "しゃ": "sha", "しゅ": "shu", "しょ": "sho", "ちゃ": "cha", "ちゅ": "chu", "ちょ": "cho",
    "にゃ": "nya", "にゅ": "nyu", "にょ": "nyo", "ひゃ": "hya", "ひゅ": "hyu", "ひょ": "hyo", "みゃ": "mya", "みゅ": "myu", "みょ": "myo",
    "りゃ": "rya", "りゅ": "ryu", "りょ": "ryo", "ぎゃ": "gya", "ぎゅ": "gyu", "ぎょ": "gyo", "じゃ": "ja", "じゅ": "ju", "じょ": "jo",
    "ぢゃ": "ja", "ぢゅ": "ju", "ぢょ": "jo", "びゃ": "bya", "びゅ": "byu", "びょ": "byo", "ぴゃ": "pya", "ぴゅ": "pyu", "ぴょ": "pyo",
    "ふぁ": "fa", "ふぃ": "fi", "ふぇ": "fe", "ふぉ": "fo", "てぃ": "ti", "でぃ": "di", "でゅ": "dyu", "うぃ": "wi", "うぇ": "we",
    "うぉ": "wo", "ゔぁ": "va", "ゔぃ": "vi", "ゔぇ": "ve", "ゔぉ": "vo", "しぇ": "she", "じぇ": "je", "ちぇ": "che", "つぁ": "tsa",
}
_kakasi = None


def to_kana(text: str) -> str:
    """Kanji and katakana to hiragana; punctuation and spaces dropped."""
    global _kakasi
    if _kakasi is None:
        import pykakasi

        _kakasi = pykakasi.kakasi()
    hira = "".join(item["hira"] for item in _kakasi.convert(text))
    return re.sub(r"[^ぁ-ゖー]", "", hira)


def moras_of_kana(s: str) -> list[tuple[str, int]]:
    """Romaji syllables of a hiragana string with the index each one starts at."""
    out: list[tuple[str, int]] = []
    i = 0
    while i < len(s):
        if s[i:i + 2] in KANA_COMBOS:
            out.append((KANA_COMBOS[s[i:i + 2]], i))
            i += 2
        elif s[i] == "ー":
            prev = out[-1][0] if out else ""
            out.append((prev[-1] if prev and prev[-1] in "aeiou" else "u", i))
            i += 1
        else:
            out.append((KANA_ROMAJI.get(s[i], s[i]), i))
            i += 1
    return out


def moras(text: str) -> list[str]:
    """'木を切っています' -> ['ki', 'o', 'ki', 'q', 'te', 'i', 'ma', 'su'] (っ is its own token, ー repeats the vowel)."""
    return [m for m, _ in moras_of_kana(to_kana(text))]


def object_span(kana: str) -> tuple[int, int]:
    """Where the object of the sentence sits in its kana: after は (or from the start) up to を / に / が.
    私は木を切っています -> 木; 雨が降ってきました -> 雨; nothing found -> (0, 0)."""
    start = kana.find("は") + 1
    for particle in "をにが":
        end = kana.find(particle, start)
        if end > start:
            return start, end
    return 0, 0


def compare_moras(expected_text: str, heard_moras: list[str], threshold: float = 0.6) -> dict:
    """Syllable-level verdict for Japanese, same shape as the Chinese one (no tones). The object of the sentence
    (木 in 私は木を切っています) must be at least half right, like the last syllable in Chinese: most sentences share
    私は and ています, so without that rule 私は土を掘っています would pass for 私は木を切っています."""
    kana = to_kana(expected_text)
    with_pos = moras_of_kana(kana)
    expected = [m for m, _ in with_pos]
    n = max(1, len(expected))
    total, per = align_words(expected, heard_moras)
    extra = max(0, len(heard_moras) - len(expected))
    score = max(0.0, (total - 0.25 * extra) / n)
    start, end = object_span(kana)
    obj = [per[i] for i, (_, pos) in enumerate(with_pos) if start <= pos < end]
    tail_ok = (sum(obj) / len(obj) >= 0.5) if obj else True
    if expected == heard_moras:
        verdict = "exact"
    elif score >= threshold and tail_ok:
        verdict = "pass"
    else:
        verdict = "wrong"
    return {
        "expected": expected,
        "heard": heard_moras,
        "score": round(score, 3),
        "per_syllable": per,
        "tail_ok": tail_ok,
        "tone_errors": 0,
        "syllable_errors": sum(1 for s in per if s < 1.0),
        "verdict": verdict,
    }


def compare_words(expected_text: str, heard_text: str, threshold: float = 0.6) -> dict:
    """Word-level verdict for Spanish and friends: same shape as the Chinese one, tones always 0.

    exact  - every word right (accents and punctuation do not count)
    pass   - similarity score >= threshold AND the last word (usually the object being learned) at least close
    wrong  - everything else
    """
    expected = words_of(expected_text)
    heard = words_of(heard_text)
    n = max(1, len(expected))
    total, per = align_words(expected, heard)
    extra = max(0, len(heard) - len(expected))
    score = max(0.0, (total - 0.25 * extra) / n)
    tail_ok = per[-1] >= 0.5 if per else False
    if expected == heard:
        verdict = "exact"
    elif score >= threshold and tail_ok:
        verdict = "pass"
    else:
        verdict = "wrong"
    return {
        "expected": expected,
        "heard": heard,
        "score": round(score, 3),
        "per_syllable": per,
        "tail_ok": tail_ok,
        "tone_errors": 0,
        "syllable_errors": sum(1 for s in per if s < 1.0),
        "verdict": verdict,
    }
