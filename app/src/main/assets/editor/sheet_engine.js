/* محرّك الجداول: تحليل وحساب الصيغ + تنسيق الأرقام + تعديل المراجع. بدون أي اعتماد خارجي. */
(function (root) {
'use strict';

const ERR = n => ({ err: n });
const isErr = v => v !== null && typeof v === 'object' && !Array.isArray(v) && v.err !== undefined;
class Unsupported extends Error {}

// ---------------------------------------------------------------- مراجع A1
function colName(n) { let s = ''; while (n > 0) { const m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = Math.floor((n - 1) / 26); } return s; }
function colIndex(s) { let n = 0; s = s.toUpperCase(); for (let i = 0; i < s.length; i++) n = n * 26 + (s.charCodeAt(i) - 64); return n; }
function parseA1(s) {
  const m = /^(\$?)([A-Za-z]{1,3})(\$?)(\d+)$/.exec(s);
  if (!m) return null;
  return { c: colIndex(m[2]), r: parseInt(m[4], 10), ac: m[1] === '$', ar: m[3] === '$' };
}
function refStr(r, c) { return colName(c) + r; }

// ---------------------------------------------------------------- المحلّل اللفظي
const REF_RE = /^(?:(?:'((?:[^']|'')+)'|([A-Za-z_\u0600-\u06FF][\w.\u0600-\u06FF]*))!)?(\$?[A-Za-z]{1,3}\$?\d+)(?::(\$?[A-Za-z]{1,3}\$?\d+))?(?![\w(])/;
const COLR_RE = /^(?:(?:'((?:[^']|'')+)'|([A-Za-z_][\w.]*))!)?(\$?[A-Za-z]{1,3}):(\$?[A-Za-z]{1,3})(?![\w(])/;
const ROWR_RE = /^(?:(?:'((?:[^']|'')+)'|([A-Za-z_][\w.]*))!)?(\$?\d+):(\$?\d+)(?![\w(])/;
const NUM_RE = /^(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?/;
const IDENT_RE = /^[A-Za-z_\u0600-\u06FF\\][\w.\u0600-\u06FF]*/;

function tokenize(src) {
  const toks = []; let i = 0; const n = src.length;
  while (i < n) {
    const ch = src[i];
    if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r') { i++; continue; }
    const rest = src.slice(i);
    let m;
    if (ch === '"') {
      let j = i + 1, s = '';
      while (j < n) { if (src[j] === '"') { if (src[j + 1] === '"') { s += '"'; j += 2; continue; } break; } s += src[j++]; }
      toks.push({ t: 'str', v: s, p: i, e: j + 1 }); i = j + 1; continue;
    }
    if (ch === '#') {
      m = /^#(?:N\/A|REF!|VALUE!|DIV\/0!|NAME\?|NUM!|NULL!)/i.exec(rest);
      if (m) { toks.push({ t: 'err', v: m[0].toUpperCase(), p: i, e: i + m[0].length }); i += m[0].length; continue; }
    }
    if ((m = REF_RE.exec(rest))) {
      toks.push({ t: 'ref', sheet: m[1] !== undefined ? m[1].replace(/''/g, "'") : m[2], a: m[3], b: m[4], p: i, e: i + m[0].length });
      i += m[0].length; continue;
    }
    if ((m = COLR_RE.exec(rest))) {
      toks.push({ t: 'colr', sheet: m[1] !== undefined ? m[1].replace(/''/g, "'") : m[2], a: m[3], b: m[4], p: i, e: i + m[0].length });
      i += m[0].length; continue;
    }
    if ((m = ROWR_RE.exec(rest))) {
      toks.push({ t: 'rowr', sheet: m[1] !== undefined ? m[1].replace(/''/g, "'") : m[2], a: m[3], b: m[4], p: i, e: i + m[0].length });
      i += m[0].length; continue;
    }
    if ((m = NUM_RE.exec(rest))) { toks.push({ t: 'num', v: parseFloat(m[0]), p: i, e: i + m[0].length }); i += m[0].length; continue; }
    if ((m = IDENT_RE.exec(rest))) {
      const id = m[0]; const nxt = src[i + id.length];
      if (nxt === '(') toks.push({ t: 'fn', v: id.toUpperCase(), p: i, e: i + id.length });
      else if (/^(TRUE|FALSE)$/i.test(id)) toks.push({ t: 'bool', v: id.toUpperCase() === 'TRUE', p: i, e: i + id.length });
      else toks.push({ t: 'name', v: id, p: i, e: i + id.length });
      i += id.length; continue;
    }
    const two = src.substr(i, 2);
    if (two === '<=' || two === '>=' || two === '<>') { toks.push({ t: 'op', v: two, p: i, e: i + 2 }); i += 2; continue; }
    if ('+-*/^&=<>%(),;{}:'.indexOf(ch) >= 0) { toks.push({ t: 'op', v: ch, p: i, e: i + 1 }); i++; continue; }
    throw new Error('bad char ' + ch);
  }
  return toks;
}

// ---------------------------------------------------------------- المحلّل النحوي
const BIN_PREC = { '=': 1, '<>': 1, '<': 1, '>': 1, '<=': 1, '>=': 1, '&': 2, '+': 3, '-': 3, '*': 4, '/': 4, '^': 5 };
const astCache = new Map();
function parse(src) {
  let a = astCache.get(src);
  if (a) return a;
  const toks = tokenize(src); let p = 0;
  const peek = () => toks[p]; const next = () => toks[p++];
  function expr(minPrec) {
    let left = unary();
    for (;;) {
      const t = peek();
      if (!t || t.t !== 'op' || !(t.v in BIN_PREC)) break;
      const pr = BIN_PREC[t.v]; if (pr < minPrec) break;
      next();
      const right = expr(pr + 1);
      left = { k: 'bin', op: t.v, l: left, r: right };
    }
    return left;
  }
  function unary() {
    const t = peek();
    if (t && t.t === 'op' && (t.v === '-' || t.v === '+')) { next(); const o = unary(); return { k: 'un', op: t.v, o }; }
    let x = primary();
    while (peek() && peek().t === 'op' && peek().v === '%') { next(); x = { k: 'pct', o: x }; }
    return x;
  }
  function primary() {
    const t = next();
    if (!t) throw new Error('unexpected end');
    switch (t.t) {
      case 'num': return { k: 'num', v: t.v };
      case 'str': return { k: 'str', v: t.v };
      case 'bool': return { k: 'bool', v: t.v };
      case 'err': return { k: 'err', v: t.v };
      case 'ref': return { k: 'ref', sheet: t.sheet, a: t.a, b: t.b };
      case 'colr': return { k: 'colr', sheet: t.sheet, a: t.a, b: t.b };
      case 'rowr': return { k: 'rowr', sheet: t.sheet, a: t.a, b: t.b };
      case 'name': return { k: 'name', v: t.v };
      case 'fn': {
        next(); // (
        const args = [];
        if (peek() && peek().t === 'op' && peek().v === ')') { next(); return { k: 'fn', name: t.v, args }; }
        for (;;) {
          const q = peek();
          if (q && q.t === 'op' && (q.v === ',' || q.v === ')')) args.push({ k: 'empty' });
          else args.push(expr(1));
          const s = next();
          if (!s) throw new Error('missing )');
          if (s.t === 'op' && s.v === ')') break;
          if (!(s.t === 'op' && (s.v === ',' || s.v === ';'))) throw new Error('bad arg sep');
        }
        return { k: 'fn', name: t.v, args };
      }
      case 'op':
        if (t.v === '(') { const e = expr(1); const c = next(); if (!c || c.v !== ')') throw new Error('missing )'); return { k: 'paren', o: e }; }
        if (t.v === '{') {
          const rows = [[]];
          for (;;) {
            const e = expr(1); rows[rows.length - 1].push(e);
            const s = next();
            if (!s) throw new Error('missing }');
            if (s.v === '}') break;
            if (s.v === ';') rows.push([]);
            else if (s.v !== ',') throw new Error('bad array');
          }
          return { k: 'arr', rows };
        }
    }
    throw new Error('unexpected token');
  }
  const ast = expr(1);
  if (p < toks.length) throw new Error('trailing tokens');
  astCache.set(src, ast);
  if (astCache.size > 20000) astCache.clear();
  return ast;
}

// ---------------------------------------------------------------- تحويلات القيم
function toNum(v) {
  if (isErr(v)) throw v;
  if (v === null || v === undefined || v === '') return 0;
  if (typeof v === 'number') return v;
  if (typeof v === 'boolean') return v ? 1 : 0;
  if (Array.isArray(v)) return toNum(v[0] && v[0][0]);
  const s = String(v).trim();
  if (/^[+-]?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?%?$/i.test(s)) return s.endsWith('%') ? parseFloat(s) / 100 : parseFloat(s);
  throw ERR('#VALUE!');
}
function toStr(v) {
  if (isErr(v)) throw v;
  if (v === null || v === undefined) return '';
  if (typeof v === 'boolean') return v ? 'TRUE' : 'FALSE';
  if (typeof v === 'number') return numToStr(v);
  if (Array.isArray(v)) return toStr(v[0] && v[0][0]);
  return String(v);
}
function toBool(v) {
  if (isErr(v)) throw v;
  if (typeof v === 'boolean') return v;
  if (v === null || v === undefined || v === '') return false;
  if (typeof v === 'number') return v !== 0;
  if (Array.isArray(v)) return toBool(v[0] && v[0][0]);
  const s = String(v).toUpperCase();
  if (s === 'TRUE') return true; if (s === 'FALSE') return false;
  throw ERR('#VALUE!');
}
function numToStr(n) {
  if (!isFinite(n)) return '#NUM!';
  if (Number.isInteger(n) && Math.abs(n) < 1e15) return String(n);
  let s = String(parseFloat(n.toPrecision(15)));
  if (/e/.test(s)) { const x = Number(s); s = Math.abs(x) >= 1e21 || Math.abs(x) < 1e-6 ? x.toExponential().replace('e', 'E') : String(x); }
  return s;
}
const scalar = v => (Array.isArray(v) ? (v[0] && v[0].length ? v[0][0] : null) : v);

// ---------------------------------------------------------------- التواريخ
const DAY_MS = 86400000;
function serialToDate(n, d1904) { return new Date(Math.round((n - (d1904 ? 24107 : 25569)) * DAY_MS)); }
function dateToSerial(d, d1904) { return d.getTime() / DAY_MS + (d1904 ? 24107 : 25569); }
function ymdToSerial(y, m, d, d1904) { return Math.floor(Date.UTC(y, m - 1, d) / DAY_MS) + (d1904 ? 24107 : 25569); }

// ---------------------------------------------------------------- المعايير (SUMIF…)
function makeCriteria(c) {
  if (typeof c === 'number' || typeof c === 'boolean') return v => (typeof v === 'number' || typeof v === 'boolean') && Number(v) === Number(c);
  const s = toStr(c);
  const m = /^(<=|>=|<>|<|>|=)?(.*)$/s.exec(s);
  const op = m[1] || '='; const rhs = m[2];
  const rn = rhs.trim() !== '' && !isNaN(Number(rhs)) ? Number(rhs) : null;
  const wild = /[*?]/.test(rhs) ? new RegExp('^' + rhs.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/~\*/g, '\u0001').replace(/\*/g, '.*').replace(/\?/g, '.').replace(/\u0001/g, '\\*') + '$', 'i') : null;
  return v => {
    if (isErr(v)) return false;
    if (rn !== null) {
      if (typeof v !== 'number') { if (typeof v === 'string' && v.trim() !== '' && !isNaN(Number(v)) && op !== '<>') v = Number(v); else return op === '<>'; }
      switch (op) { case '=': return v === rn; case '<>': return v !== rn; case '<': return v < rn; case '>': return v > rn; case '<=': return v <= rn; case '>=': return v >= rn; }
    }
    const sv = v === null || v === undefined ? '' : (typeof v === 'string' ? v : toStr(v));
    if (op === '=' && rhs === '') return v === null || v === undefined || v === '';
    if (wild && (op === '=' || op === '<>')) return op === '=' ? wild.test(sv) : !wild.test(sv);
    const a = sv.toLowerCase(), b = rhs.toLowerCase();
    switch (op) { case '=': return a === b; case '<>': return a !== b; case '<': return a < b; case '>': return a > b; case '<=': return a <= b; case '>=': return a >= b; }
    return false;
  };
}

// ---------------------------------------------------------------- الدوال
function flat(args) {
  const out = [];
  for (const a of args) {
    if (Array.isArray(a)) { for (const row of a) for (const v of row) out.push({ v, rng: true }); }
    else out.push({ v: a, rng: false });
  }
  return out;
}
function nums(args) {
  const out = [];
  for (const { v, rng } of flat(args)) {
    if (isErr(v)) throw v;
    if (typeof v === 'number') out.push(v);
    else if (!rng) { if (typeof v === 'boolean') out.push(v ? 1 : 0); else if (v !== null && v !== '' && v !== undefined) out.push(toNum(v)); }
  }
  return out;
}
function broadcast(a, b, fn) {
  const A = Array.isArray(a), B = Array.isArray(b);
  if (!A && !B) return fn(a, b);
  const ra = A ? a.length : 1, ca = A ? a[0].length : 1, rb = B ? b.length : 1, cb = B ? b[0].length : 1;
  const R = Math.max(ra, rb), C = Math.max(ca, cb), out = [];
  for (let i = 0; i < R; i++) { const row = []; for (let j = 0; j < C; j++) {
    const x = A ? (a[ra === 1 ? 0 : i] || [])[ca === 1 ? 0 : j] : a; const y = B ? (b[rb === 1 ? 0 : i] || [])[cb === 1 ? 0 : j] : b;
    try { row.push(fn(x === undefined ? ERR('#N/A') : x, y === undefined ? ERR('#N/A') : y)); } catch (e) { if (isErr(e)) row.push(e); else throw e; }
  } out.push(row); }
  return out;
}
function cmpVals(a, b) {
  const ta = typeof a === 'number' ? 1 : typeof a === 'string' ? 2 : typeof a === 'boolean' ? 3 : 0;
  const tb = typeof b === 'number' ? 1 : typeof b === 'string' ? 2 : typeof b === 'boolean' ? 3 : 0;
  if (a === null || a === undefined) { if (tb === 2) a = ''; else if (tb === 3) a = false; else a = 0; }
  if (b === null || b === undefined) { if (ta === 2) b = ''; else if (ta === 3) b = false; else b = 0; }
  const ra = typeof a === 'number' ? 1 : typeof a === 'string' ? 2 : 3, rb = typeof b === 'number' ? 1 : typeof b === 'string' ? 2 : 3;
  if (ra !== rb) return ra < rb ? -1 : 1;
  if (ra === 2) { const x = a.toLowerCase(), y = b.toLowerCase(); return x < y ? -1 : x > y ? 1 : 0; }
  return a < b ? -1 : a > b ? 1 : 0;
}
function roundTo(x, d, mode) {
  const f = Math.pow(10, d); const y = x * f;
  let r; if (mode === 'up') r = Math.sign(y) * Math.ceil(Math.abs(y) - 1e-12); else if (mode === 'down') r = Math.sign(y) * Math.floor(Math.abs(y) + 1e-12);
  else r = Math.sign(y) * Math.round(Math.abs(y) + 1e-12);
  return r / f;
}
function matchLookup(look, arr, type) {
  if (type === 0) { for (let i = 0; i < arr.length; i++) if (cmpVals(look, arr[i]) === 0 && typeof look === typeof arr[i]) return i; return -1; }
  let best = -1;
  if (type === 1) { for (let i = 0; i < arr.length; i++) { if (arr[i] === null || arr[i] === '') continue; if (cmpVals(arr[i], look) <= 0 && typeof arr[i] === typeof look) best = i; else if (typeof arr[i] === typeof look) break; } }
  else { for (let i = 0; i < arr.length; i++) { if (cmpVals(arr[i], look) >= 0 && typeof arr[i] === typeof look) best = i; else break; } }
  return best;
}
const FN = {
  SUM: (a) => nums(a).reduce((x, y) => x + y, 0),
  AVERAGE: (a) => { const n = nums(a); if (!n.length) throw ERR('#DIV/0!'); return n.reduce((x, y) => x + y, 0) / n.length; },
  MIN: (a) => { const n = nums(a); return n.length ? Math.min(...n) : 0; },
  MAX: (a) => { const n = nums(a); return n.length ? Math.max(...n) : 0; },
  COUNT: (a) => flat(a).filter(({ v, rng }) => typeof v === 'number' || (!rng && typeof v === 'boolean') || (!rng && typeof v === 'string' && v.trim() !== '' && !isNaN(Number(v)))).length,
  COUNTA: (a) => flat(a).filter(({ v }) => v !== null && v !== undefined && v !== '').length,
  COUNTBLANK: (a) => flat(a).filter(({ v }) => v === null || v === undefined || v === '').length,
  MEDIAN: (a) => { const n = nums(a).sort((x, y) => x - y); if (!n.length) throw ERR('#NUM!'); const m = n.length >> 1; return n.length % 2 ? n[m] : (n[m - 1] + n[m]) / 2; },
  STDEV: (a) => { const n = nums(a); if (n.length < 2) throw ERR('#DIV/0!'); const m = n.reduce((x, y) => x + y, 0) / n.length; return Math.sqrt(n.reduce((s, x) => s + (x - m) * (x - m), 0) / (n.length - 1)); },
  LARGE: (a) => { const n = nums([a[0]]).sort((x, y) => y - x); const k = toNum(a[1]); if (k < 1 || k > n.length) throw ERR('#NUM!'); return n[k - 1]; },
  SMALL: (a) => { const n = nums([a[0]]).sort((x, y) => x - y); const k = toNum(a[1]); if (k < 1 || k > n.length) throw ERR('#NUM!'); return n[k - 1]; },
  RANK: (a) => { const x = toNum(a[0]); const n = nums([a[1]]); const asc = a[2] !== undefined && toNum(a[2]) !== 0; if (!n.includes(x)) throw ERR('#N/A'); return 1 + n.filter(v => asc ? v < x : v > x).length; },
  ABS: (a) => Math.abs(toNum(a[0])), SQRT: (a) => { const x = toNum(a[0]); if (x < 0) throw ERR('#NUM!'); return Math.sqrt(x); },
  INT: (a) => Math.floor(toNum(a[0])), SIGN: (a) => Math.sign(toNum(a[0])), EXP: (a) => Math.exp(toNum(a[0])),
  LN: (a) => { const x = toNum(a[0]); if (x <= 0) throw ERR('#NUM!'); return Math.log(x); },
  LOG10: (a) => { const x = toNum(a[0]); if (x <= 0) throw ERR('#NUM!'); return Math.log10(x); },
  LOG: (a) => { const x = toNum(a[0]); if (x <= 0) throw ERR('#NUM!'); return Math.log(x) / Math.log(a[1] === undefined ? 10 : toNum(a[1])); },
  PI: () => Math.PI, RAND: () => Math.random(),
  RANDBETWEEN: (a) => { const lo = Math.ceil(toNum(a[0])), hi = Math.floor(toNum(a[1])); return lo + Math.floor(Math.random() * (hi - lo + 1)); },
  POWER: (a) => { const r = Math.pow(toNum(a[0]), toNum(a[1])); if (!isFinite(r)) throw ERR('#NUM!'); return r; },
  MOD: (a) => { const x = toNum(a[0]), y = toNum(a[1]); if (y === 0) throw ERR('#DIV/0!'); return x - y * Math.floor(x / y); },
  ROUND: (a) => roundTo(toNum(a[0]), a[1] === undefined ? 0 : toNum(a[1]), 'n'),
  ROUNDUP: (a) => roundTo(toNum(a[0]), a[1] === undefined ? 0 : toNum(a[1]), 'up'),
  ROUNDDOWN: (a) => roundTo(toNum(a[0]), a[1] === undefined ? 0 : toNum(a[1]), 'down'),
  CEILING: (a) => { const s = a[1] === undefined ? 1 : toNum(a[1]); return s === 0 ? 0 : Math.ceil(toNum(a[0]) / s) * s; },
  FLOOR: (a) => { const s = a[1] === undefined ? 1 : toNum(a[1]); return s === 0 ? 0 : Math.floor(toNum(a[0]) / s) * s; },
  AND: (a) => { const v = flat(a).filter(x => !(x.rng && (x.v === null || typeof x.v === 'string'))).map(x => toBool(x.v)); if (!v.length) throw ERR('#VALUE!'); return v.every(Boolean); },
  OR: (a) => { const v = flat(a).filter(x => !(x.rng && (x.v === null || typeof x.v === 'string'))).map(x => toBool(x.v)); if (!v.length) throw ERR('#VALUE!'); return v.some(Boolean); },
  NOT: (a) => !toBool(a[0]), TRUE: () => true, FALSE: () => false,
  LEN: (a) => toStr(a[0]).length,
  LEFT: (a) => toStr(a[0]).slice(0, a[1] === undefined ? 1 : Math.max(0, toNum(a[1]))),
  RIGHT: (a) => { const s = toStr(a[0]); const k = a[1] === undefined ? 1 : Math.max(0, toNum(a[1])); return k === 0 ? '' : s.slice(-k); },
  MID: (a) => { const st = toNum(a[1]); if (st < 1) throw ERR('#VALUE!'); return toStr(a[0]).substr(st - 1, Math.max(0, toNum(a[2]))); },
  UPPER: (a) => toStr(a[0]).toUpperCase(), LOWER: (a) => toStr(a[0]).toLowerCase(),
  PROPER: (a) => toStr(a[0]).toLowerCase().replace(/(^|[^a-z\u00C0-\u024F])([a-z\u00C0-\u024F])/g, (m, p, c) => p + c.toUpperCase()),
  TRIM: (a) => toStr(a[0]).trim().replace(/ +/g, ' '),
  CONCAT: (a) => flat(a).map(x => toStr(x.v)).join(''), CONCATENATE: (a) => a.map(toStr).join(''),
  TEXTJOIN: (a) => { const d = toStr(a[0]), ig = toBool(a[1]); return flat(a.slice(2)).map(x => x.v).filter(v => !(ig && (v === null || v === ''))).map(toStr).join(d); },
  EXACT: (a) => toStr(a[0]) === toStr(a[1]),
  REPT: (a) => toStr(a[0]).repeat(Math.max(0, toNum(a[1]))),
  FIND: (a) => { const i = toStr(a[1]).indexOf(toStr(a[0]), a[2] === undefined ? 0 : toNum(a[2]) - 1); if (i < 0) throw ERR('#VALUE!'); return i + 1; },
  SEARCH: (a) => { const i = toStr(a[1]).toLowerCase().indexOf(toStr(a[0]).toLowerCase(), a[2] === undefined ? 0 : toNum(a[2]) - 1); if (i < 0) throw ERR('#VALUE!'); return i + 1; },
  SUBSTITUTE: (a) => { const s = toStr(a[0]), f = toStr(a[1]), t = toStr(a[2]); if (!f) return s; if (a[3] === undefined) return s.split(f).join(t); let n = toNum(a[3]), idx = -1; while (n-- > 0) { idx = s.indexOf(f, idx + 1); if (idx < 0) return s; } return s.slice(0, idx) + t + s.slice(idx + f.length); },
  REPLACE: (a) => { const s = toStr(a[0]), st = toNum(a[1]) - 1, ln = toNum(a[2]); return s.slice(0, st) + toStr(a[3]) + s.slice(st + ln); },
  VALUE: (a) => toNum(a[0]), N: (a) => { const v = scalar(a[0]); return typeof v === 'number' ? v : typeof v === 'boolean' ? (v ? 1 : 0) : 0; }, T: (a) => { const v = scalar(a[0]); return typeof v === 'string' ? v : ''; },
  ISNUMBER: (a) => typeof scalar(a[0]) === 'number', ISTEXT: (a) => typeof scalar(a[0]) === 'string',
  ISBLANK: (a) => { const v = scalar(a[0]); return v === null || v === undefined; },
  ISERROR: (a) => isErr(scalar(a[0])), ISNA: (a) => { const v = scalar(a[0]); return isErr(v) && v.err === '#N/A'; },
  ISLOGICAL: (a) => typeof scalar(a[0]) === 'boolean',
  ROW: (a, ctx) => a.length && a[0] && a[0].__ref ? a[0].__ref.r1 : ctx.row, COLUMN: (a, ctx) => a.length && a[0] && a[0].__ref ? a[0].__ref.c1 : ctx.col,
  ROWS: (a) => Array.isArray(a[0]) ? a[0].length : 1, COLUMNS: (a) => Array.isArray(a[0]) ? a[0][0].length : 1,
  SUMIF: (a) => { const rng = a[0], crit = makeCriteria(a[1]); const sr = a[2] || rng; let s = 0; for (let i = 0; i < rng.length; i++) for (let j = 0; j < rng[i].length; j++) if (crit(rng[i][j])) { const v = (sr[i] || [])[j]; if (typeof v === 'number') s += v; } return s; },
  COUNTIF: (a) => { const crit = makeCriteria(a[1]); let n = 0; for (const r of a[0]) for (const v of r) if (crit(v)) n++; return n; },
  AVERAGEIF: (a) => { const rng = a[0], crit = makeCriteria(a[1]); const sr = a[2] || rng; let s = 0, n = 0; for (let i = 0; i < rng.length; i++) for (let j = 0; j < rng[i].length; j++) if (crit(rng[i][j])) { const v = (sr[i] || [])[j]; if (typeof v === 'number') { s += v; n++; } } if (!n) throw ERR('#DIV/0!'); return s / n; },
  SUMIFS: (a) => { const sr = a[0]; const pairs = []; for (let k = 1; k + 1 < a.length; k += 2) pairs.push([a[k], makeCriteria(a[k + 1])]); let s = 0; for (let i = 0; i < sr.length; i++) for (let j = 0; j < sr[i].length; j++) if (pairs.every(([r, c]) => c((r[i] || [])[j])) && typeof sr[i][j] === 'number') s += sr[i][j]; return s; },
  COUNTIFS: (a) => { const pairs = []; for (let k = 0; k + 1 < a.length; k += 2) pairs.push([a[k], makeCriteria(a[k + 1])]); const r0 = a[0]; let n = 0; for (let i = 0; i < r0.length; i++) for (let j = 0; j < r0[i].length; j++) if (pairs.every(([r, c]) => c((r[i] || [])[j]))) n++; return n; },
  SUMPRODUCT: (a) => { let s = 0; const first = a[0]; if (!Array.isArray(first)) return toNum(first) * (a.length > 1 ? toNum(a[1]) : 1); for (let i = 0; i < first.length; i++) for (let j = 0; j < first[i].length; j++) { let p = 1; for (const m of a) { const v = Array.isArray(m) ? (m[i] || [])[j] : m; p *= typeof v === 'number' ? v : typeof v === 'boolean' ? (v ? 1 : 0) : 0; } s += p; } return s; },
  VLOOKUP: (a) => { const look = scalar(a[0]), tbl = a[1], ci = toNum(a[2]); const exact = a[3] !== undefined && !toBool(a[3]); if (ci < 1 || ci > tbl[0].length) throw ERR('#REF!'); const col = tbl.map(r => r[0]); const i = matchLookup(look, col, exact ? 0 : 1); if (i < 0) throw ERR('#N/A'); return tbl[i][ci - 1]; },
  HLOOKUP: (a) => { const look = scalar(a[0]), tbl = a[1], ri = toNum(a[2]); const exact = a[3] !== undefined && !toBool(a[3]); if (ri < 1 || ri > tbl.length) throw ERR('#REF!'); const i = matchLookup(look, tbl[0], exact ? 0 : 1); if (i < 0) throw ERR('#N/A'); return tbl[ri - 1][i]; },
  MATCH: (a) => { const arr = [].concat(...a[1]); const i = matchLookup(scalar(a[0]), arr, a[2] === undefined ? 1 : toNum(a[2])); if (i < 0) throw ERR('#N/A'); return i + 1; },
  INDEX: (a) => { const t = a[0]; if (!Array.isArray(t)) return t; let r = a[1] === undefined ? 0 : toNum(a[1]), c = a[2] === undefined ? 0 : toNum(a[2]); if (t.length === 1 && a[2] === undefined) { c = r; r = 1; } if (r === 0 && c > 0) return t.map(x => [x[c - 1]]); if (c === 0 && r > 0 && t[0].length > 1 && a[2] !== undefined) return [t[r - 1]]; r = r || 1; c = c || 1; if (r > t.length || c > t[0].length || r < 1 || c < 1) throw ERR('#REF!'); return t[r - 1][c - 1]; },
  XLOOKUP: (a) => { const look = scalar(a[0]); const la = [].concat(...a[1]), ra = [].concat(...a[2]); const i = matchLookup(look, la, 0); if (i < 0) { if (a[3] !== undefined) return a[3]; throw ERR('#N/A'); } return ra[i]; },
  TODAY: (a, ctx) => Math.floor(dateToSerial(new Date(), ctx.d1904)), NOW: (a, ctx) => dateToSerial(new Date(Date.now() - new Date().getTimezoneOffset() * 60000), ctx.d1904),
  DATE: (a, ctx) => { let y = toNum(a[0]), m = toNum(a[1]), d = toNum(a[2]); if (y < 1900) y += 1900; return ymdToSerial(y, m, d, ctx.d1904); },
  YEAR: (a, ctx) => serialToDate(toNum(a[0]), ctx.d1904).getUTCFullYear(), MONTH: (a, ctx) => serialToDate(toNum(a[0]), ctx.d1904).getUTCMonth() + 1, DAY: (a, ctx) => serialToDate(toNum(a[0]), ctx.d1904).getUTCDate(),
  HOUR: (a) => Math.floor(((toNum(a[0]) % 1) * 24) + 1e-9), MINUTE: (a) => Math.floor(((toNum(a[0]) % 1) * 1440) + 1e-9) % 60, SECOND: (a) => Math.round((toNum(a[0]) % 1) * 86400) % 60,
  WEEKDAY: (a, ctx) => { const d = serialToDate(toNum(a[0]), ctx.d1904).getUTCDay(); const t = a[1] === undefined ? 1 : toNum(a[1]); return t === 2 ? (d === 0 ? 7 : d) : t === 3 ? (d + 6) % 7 : d + 1; },
  TEXT: (a, ctx) => formatValue(scalar(a[0]), toStr(a[1]), { d1904: ctx.d1904 }).text,
};
const LAZY = {
  IF: (args, ev) => { const c = toBool(ev(args[0])); if (c) return args[1] ? ev(args[1], true) : true; return args[2] ? ev(args[2], true) : false; },
  IFERROR: (args, ev) => { try { const v = ev(args[0], true); if (isErr(scalar(v))) return ev(args[1], true); return v; } catch (e) { if (isErr(e)) return ev(args[1], true); throw e; } },
  IFNA: (args, ev) => { try { const v = ev(args[0], true); if (isErr(scalar(v)) && scalar(v).err === '#N/A') return ev(args[1], true); return v; } catch (e) { if (isErr(e) && e.err === '#N/A') return ev(args[1], true); throw e; } },
  CHOOSE: (args, ev) => { const i = toNum(ev(args[0])); if (i < 1 || i >= args.length) throw ERR('#VALUE!'); return ev(args[Math.floor(i)], true); },
};

// ---------------------------------------------------------------- المصنّف والتقييم
class Engine {
  /** wb = { sheets:[{name, cells:Map<number,cell>}], d1904 }  — المفتاح = r*16384 + c */
  constructor(wb) { this.wb = wb; this.pass = 0; }
  sheetIndex(name, cur) {
    if (name === undefined || name === null) return cur;
    const l = name.toLowerCase();
    for (let i = 0; i < this.wb.sheets.length; i++) if (this.wb.sheets[i].name.toLowerCase() === l) return i;
    return -1;
  }
  getCell(si, r, c) { return this.wb.sheets[si].cells.get(r * 16384 + c); }
  valueAt(si, r, c) {
    const cell = this.getCell(si, r, c);
    if (!cell) return null;
    if (cell.f !== undefined && cell._p !== this.pass) this.computeCell(cell, si, r, c);
    return cell.v === undefined ? null : cell.v;
  }
  computeCell(cell, si, r, c) {
    if (cell._busy) { cell.v = ERR('#REF!'); cell._p = this.pass; return; }
    cell._busy = true;
    try {
      const ast = parse(cell.f);
      const ctx = { si, row: r, col: c, d1904: this.wb.d1904 };
      let v = this.evalNode(ast, ctx, true);
      if (Array.isArray(v)) v = v[0] && v[0].length ? v[0][0] : null;
      cell.v = v === undefined ? null : v;
      if (cell.v === null) cell.v = 0;
      cell.unsup = false;
    } catch (e) {
      if (isErr(e)) { cell.v = e; cell.unsup = false; }
      else if (e instanceof Unsupported) { cell.unsup = true; /* نُبقي القيمة المخزّنة في الملف */ }
      else { cell.v = ERR('#NAME?'); }
    } finally { cell._busy = false; cell._p = this.pass; }
  }
  recalc() {
    this.pass++;
    for (let si = 0; si < this.wb.sheets.length; si++) {
      for (const [k, cell] of this.wb.sheets[si].cells) if (cell.f !== undefined && cell._p !== this.pass) this.computeCell(cell, si, Math.floor(k / 16384), k % 16384);
    }
  }
  resolveRef(node, ctx) {
    const si = this.sheetIndex(node.sheet, ctx.si);
    if (si < 0) throw ERR('#REF!');
    let r1, c1, r2, c2;
    const sh = this.wb.sheets[si];
    if (node.k === 'ref') {
      const a = parseA1(node.a), b = node.b ? parseA1(node.b) : a;
      r1 = Math.min(a.r, b.r); r2 = Math.max(a.r, b.r); c1 = Math.min(a.c, b.c); c2 = Math.max(a.c, b.c);
    } else if (node.k === 'colr') {
      c1 = colIndex(node.a.replace('$', '')); c2 = colIndex(node.b.replace('$', '')); if (c1 > c2) [c1, c2] = [c2, c1]; r1 = 1; r2 = this.usedRows(sh);
    } else {
      r1 = parseInt(node.a.replace('$', ''), 10); r2 = parseInt(node.b.replace('$', ''), 10); if (r1 > r2) [r1, r2] = [r2, r1]; c1 = 1; c2 = this.usedCols(sh);
    }
    return { si, r1, c1, r2, c2 };
  }
  usedRows(sh) { let m = 1; for (const k of sh.cells.keys()) { const r = Math.floor(k / 16384); if (r > m) m = r; } return m; }
  usedCols(sh) { let m = 1; for (const k of sh.cells.keys()) { const c = k % 16384; if (c > m) m = c; } return m; }
  evalNode(n, ctx, keepArray) {
    const ev = (x, keep) => this.evalNode(x, ctx, keep);
    switch (n.k) {
      case 'num': case 'str': case 'bool': return n.v;
      case 'err': throw ERR(n.v);
      case 'empty': return null;
      case 'paren': return ev(n.o, keepArray);
      case 'name': throw ERR('#NAME?');
      case 'ref': case 'colr': case 'rowr': {
        const rf = this.resolveRef(n, ctx);
        if (rf.r1 === rf.r2 && rf.c1 === rf.c2 && n.k === 'ref' && !n.b) { const v = this.valueAt(rf.si, rf.r1, rf.c1); if (keepArray === 'ref') return { __ref: rf, v }; return v; }
        const rows = [];
        for (let r = rf.r1; r <= rf.r2; r++) { const row = []; for (let c = rf.c1; c <= rf.c2; c++) row.push(this.valueAt(rf.si, r, c)); rows.push(row); }
        rows.__ref = rf;
        return rows;
      }
      case 'arr': return n.rows.map(r => r.map(e => scalar(ev(e))));
      case 'un': { const v = ev(n.o); if (Array.isArray(v)) return broadcast(v, 0, (x) => n.op === '-' ? -toNum(x) : toNum(x)); const x = toNum(v); return n.op === '-' ? -x : x; }
      case 'pct': { const v = ev(n.o); return Array.isArray(v) ? broadcast(v, 0, x => toNum(x) / 100) : toNum(v) / 100; }
      case 'bin': {
        const l = ev(n.l), r = ev(n.r);
        const f = (x, y) => {
          if (isErr(x)) throw x; if (isErr(y)) throw y;
          switch (n.op) {
            case '+': return toNum(x) + toNum(y); case '-': return toNum(x) - toNum(y); case '*': return toNum(x) * toNum(y);
            case '/': { const d = toNum(y); if (d === 0) throw ERR('#DIV/0!'); return toNum(x) / d; }
            case '^': { const v = Math.pow(toNum(x), toNum(y)); if (!isFinite(v)) throw ERR('#NUM!'); return v; }
            case '&': return toStr(x) + toStr(y);
            case '=': return cmpVals(x, y) === 0 && (x === null || y === null || typeof x === typeof y);
            case '<>': return !(cmpVals(x, y) === 0 && (x === null || y === null || typeof x === typeof y));
            case '<': return cmpVals(x, y) < 0; case '>': return cmpVals(x, y) > 0;
            case '<=': return cmpVals(x, y) <= 0; case '>=': return cmpVals(x, y) >= 0;
          }
        };
        return broadcast(l, r, f);
      }
      case 'fn': {
        if (LAZY[n.name]) return LAZY[n.name](n.args, (x, keep) => ev(x, keep));
        const f = FN[n.name];
        if (!f) throw new Unsupported(n.name);
        const isRC = n.name === 'ROW' || n.name === 'COLUMN';
        const args = n.args.map(a => (isRC && a.k === 'ref') ? ev(a, 'ref') : ev(a, true));
        const fixed = args.map(a => (a && a.__ref && !Array.isArray(a) && !isRC ? a.v : a));
        return f(fixed, ctx);
      }
    }
    throw new Error('bad node');
  }
  /** تقييم صيغة نصية مباشرة (للعرض الفوري) */
  evalFormula(f, si, r, c) { const ctx = { si, row: r, col: c, d1904: this.wb.d1904 }; try { let v = this.evalNode(parse(f), ctx, true); if (Array.isArray(v)) v = scalar(v); return v; } catch (e) { if (isErr(e)) return e; throw e; } }
}

// ---------------------------------------------------------------- إعادة كتابة المراجع (نسخ/إدراج/حذف)
/** يطبّق mapper على كل مرجع في الصيغة. mapper({sheet,a,b,kind}) يعيد نصّ المرجع الجديد أو {sheet,text} أو null → #REF! */
function rewriteFormula(f, mapper) {
  let toks; try { toks = tokenize(f); } catch (e) { return f; }
  let out = '', last = 0;
  for (const t of toks) {
    if (t.t !== 'ref' && t.t !== 'colr' && t.t !== 'rowr') continue;
    out += f.slice(last, t.p);
    let nv = mapper({ sheet: t.sheet, a: t.a, b: t.b, kind: t.t });
    let sheetName = t.sheet;
    if (nv !== null && typeof nv === 'object') { sheetName = nv.sheet; nv = nv.text; }
    const prefix = sheetName !== undefined ? (/^[A-Za-z_][\w.]*$/.test(sheetName) ? sheetName : "'" + sheetName.replace(/'/g, "''") + "'") + '!' : '';
    out += nv === null ? '#REF!' : prefix + nv;
    last = t.e;
  }
  return out + f.slice(last);
}
function shiftRefText(a, dr, dc) {
  const p = parseA1(a); if (!p) return a;
  const r = p.ar ? p.r : p.r + dr, c = p.ac ? p.c : p.c + dc;
  if (r < 1 || c < 1 || r > 1048576 || c > 16384) return null;
  return (p.ac ? '$' : '') + colName(c) + (p.ar ? '$' : '') + r;
}
function shiftFormula(f, dr, dc) {
  return rewriteFormula(f, ({ a, b, kind }) => {
    if (kind === 'ref') { const x = shiftRefText(a, dr, dc); if (x === null) return null; if (!b) return x; const y = shiftRefText(b, dr, dc); return y === null ? null : x + ':' + y; }
    if (kind === 'colr') { const sh = s => { const ab = s[0] === '$'; if (ab) return s; const c = colIndex(s) + dc; return c < 1 ? null : colName(c); }; const x = sh(a), y = sh(b); return x === null || y === null ? null : x + ':' + y; }
    const sh = s => { const ab = s[0] === '$'; if (ab) return s; const r = parseInt(s, 10) + dr; return r < 1 ? null : String(r); };
    const x = sh(a), y = sh(b); return x === null || y === null ? null : x + ':' + y;
  });
}
/** تعديل المراجع بعد إدراج/حذف صفوف أو أعمدة. axis 'r'|'c', at = أول فهرس متأثّر، count>0 إدراج، <0 حذف */
function adjustForStructure(f, ownSheetName, targetSheetName, axis, at, count) {
  return rewriteFormula(f, ({ sheet, a, b, kind }) => {
    const sn = sheet === undefined ? ownSheetName : sheet;
    if (sn.toLowerCase() !== targetSheetName.toLowerCase()) return b ? a + ':' + b : a;
    const adj = (idx, isEnd) => {
      if (count > 0) return idx >= at ? idx + count : idx;
      const del = -count;
      if (idx < at) return idx;
      if (idx >= at + del) return idx - del;
      return isEnd ? at - 1 : at;
    };
    const inDeleted = idx => count < 0 && idx >= at && idx < at - count;
    function one(txt, isEnd, single) {
      const p = parseA1(txt);
      if (!p) return txt;
      const v = axis === 'r' ? p.r : p.c;
      if (single && inDeleted(v)) return null;
      const nv = adj(v, isEnd);
      if (nv < 1) return null;
      const r = axis === 'r' ? nv : p.r, c = axis === 'c' ? nv : p.c;
      return (p.ac ? '$' : '') + colName(c) + (p.ar ? '$' : '') + r;
    }
    if (kind === 'ref') {
      if (!b) return one(a, false, true);
      const x = one(a, false, false), y = one(b, true, false);
      if (x === null || y === null) return null;
      const px = parseA1(x), py = parseA1(y);
      if (count < 0 && (axis === 'r' ? py.r < px.r : py.c < px.c)) return null;
      return x + ':' + y;
    }
    if (kind === 'colr' && axis === 'c') { const ca = colIndex(a.replace('$', '')), cb = colIndex(b.replace('$', '')); const x = adj(ca, false), y = adj(cb, true); return x < 1 || y < x ? null : colName(x) + ':' + colName(y); }
    if (kind === 'rowr' && axis === 'r') { const ra = parseInt(a.replace('$', ''), 10), rb = parseInt(b.replace('$', ''), 10); const x = adj(ra, false), y = adj(rb, true); return x < 1 || y < x ? null : x + ':' + y; }
    return a + ':' + b;
  });
}

// ---------------------------------------------------------------- تنسيق الأرقام
const MONTHS_EN = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const DAYS_EN = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
function splitSections(code) {
  const out = []; let cur = '', q = false, esc = false;
  for (const ch of code) { if (esc) { cur += ch; esc = false; continue; } if (ch === '\\') { cur += ch; esc = true; continue; } if (ch === '"') q = !q; if (ch === ';' && !q) { out.push(cur); cur = ''; } else cur += ch; }
  out.push(cur); return out;
}
function tokenizeFmt(sec) {
  const toks = []; let i = 0;
  while (i < sec.length) {
    const ch = sec[i];
    if (ch === '"') { const j = sec.indexOf('"', i + 1); const e = j < 0 ? sec.length : j; toks.push({ t: 'lit', v: sec.slice(i + 1, e) }); i = e + 1; continue; }
    if (ch === '\\') { toks.push({ t: 'lit', v: sec[i + 1] || '' }); i += 2; continue; }
    if (ch === '_') { toks.push({ t: 'lit', v: ' ' }); i += 2; continue; }
    if (ch === '*') { i += 2; continue; }
    if (ch === '[') { const j = sec.indexOf(']', i); const e = j < 0 ? sec.length : j; const inner = sec.slice(i + 1, e);
      if (/^(h+|m+|s+)$/i.test(inner)) toks.push({ t: 'elapsed', v: inner.toLowerCase() });
      else if (/^\$[^-\]]*(-.*)?$/.test(inner)) { const cur = inner.slice(1).split('-')[0]; if (cur) toks.push({ t: 'lit', v: cur }); }
      else if (/^(red|blue|green|black|white|cyan|magenta|yellow|color\s*\d+)$/i.test(inner)) toks.push({ t: 'color', v: inner.toLowerCase() });
      i = e + 1; continue; }
    const rest = sec.slice(i);
    let m;
    if ((m = /^(AM\/PM|A\/P)/i.exec(rest))) { toks.push({ t: 'ampm', v: m[0] }); i += m[0].length; continue; }
    if ((m = /^E[+-]/i.exec(rest))) { toks.push({ t: 'exp', v: m[0] }); i += 2; continue; }
    if ((m = /^(yyyy|yy|mmmmm|mmmm|mmm|mm|m|dddd|ddd|dd|d|hh|h|ss|s|b2|b1)/i.exec(rest))) { toks.push({ t: 'dt', v: m[0].toLowerCase() }); i += m[0].length; continue; }
    if ('0#?'.indexOf(ch) >= 0) { toks.push({ t: 'dig', v: ch }); i++; continue; }
    if (ch === '.' || ch === ',' || ch === '%') { toks.push({ t: ch === '%' ? 'pct' : 'sep', v: ch }); i++; continue; }
    if (ch === '@') { toks.push({ t: 'at' }); i++; continue; }
    toks.push({ t: 'lit', v: ch }); i++;
  }
  return toks;
}
function fmtNumberSection(toks, x, neg) {
  let pct = 0; for (const t of toks) if (t.t === 'pct') pct++;
  x = Math.abs(x) * Math.pow(100, pct);
  const expTok = toks.find(t => t.t === 'exp');
  const digToks = toks.filter(t => t.t === 'dig');
  const dotIdx = toks.findIndex(t => t.t === 'sep' && t.v === '.');
  let intPat = '', decPat = '', seenDot = false, thousands = false, scaleCommas = 0;
  for (let i = 0; i < toks.length; i++) {
    const t = toks[i];
    if (t.t === 'exp') break;
    if (t.t === 'sep' && t.v === '.') seenDot = true;
    else if (t.t === 'dig') { if (seenDot) decPat += t.v; else intPat += t.v; }
    else if (t.t === 'sep' && t.v === ',') {
      let before = false, after = false;
      for (let j = i - 1; j >= 0; j--) if (toks[j].t === 'dig') { before = true; break; }
      for (let j = i + 1; j < toks.length; j++) if (toks[j].t === 'dig') { after = true; break; }
      if (before && after) thousands = true; else if (before && !after) scaleCommas++;
    }
  }
  x = x / Math.pow(1000, scaleCommas);
  let expStr = '';
  if (expTok) {
    let e = x === 0 ? 0 : Math.floor(Math.log10(x)); const intDigits = Math.max(1, intPat.length);
    e = e - (intDigits - 1); x = x / Math.pow(10, e);
    expStr = (expTok.v[1] === '+' && e >= 0 ? '+' : e < 0 ? '-' : (expTok.v[1] === '+' ? '+' : '')) + String(Math.abs(e)).padStart(2, '0');
  }
  let s = x.toFixed(Math.min(decPat.length, 20));
  if (!digToks.length) s = '';
  let [ip, dp = ''] = s.split('.');
  let dpOut = dp;
  for (let i = decPat.length - 1; i >= 0; i--) { if (decPat[i] !== '0' && dpOut[i] === '0' && dpOut.length === i + 1) dpOut = dpOut.slice(0, -1); else if (decPat[i] === '?' && dpOut[i] === '0' && dpOut.length === i + 1) dpOut = dpOut.slice(0, -1) + ' '; else break; }
  const minInt = (intPat.match(/0/g) || []).length;
  if (digToks.length) { ip = ip.replace(/^0+/, ''); if (ip.length < minInt) ip = ip.padStart(minInt, '0'); }
  if (thousands) ip = ip.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  let res = '', placedNum = false, afterExp = false;
  for (const t of toks) {
    if (afterExp && t.t === 'dig') continue;
    if (t.t === 'dig' || (t.t === 'sep')) {
      if (!placedNum && (t.t === 'dig' || t.v === '.')) { placedNum = true; res += ip + (decPat.length ? '.' + dpOut : ''); }
    } else if (t.t === 'lit') res += t.v;
    else if (t.t === 'pct') res += '%';
    else if (t.t === 'exp') { res += 'E' + expStr; afterExp = true; }
  }
  if (neg) res = '-' + res;
  return res;
}
function fmtDateSection(toks, x, d1904) {
  const hasAmPm = toks.some(t => t.t === 'ampm');
  const d = serialToDate(x, d1904);
  if (isNaN(d)) return '#####';
  const y = d.getUTCFullYear(), mo = d.getUTCMonth(), da = d.getUTCDate(), wd = d.getUTCDay();
  let secs = Math.round((x - Math.floor(x)) * 86400); if (secs >= 86400) secs -= 86400;
  const H = Math.floor(secs / 3600), M = Math.floor(secs / 60) % 60, S = secs % 60;
  let out = '';
  for (let i = 0; i < toks.length; i++) {
    const t = toks[i];
    if (t.t === 'dt') {
      const v = t.v;
      if (v === 'm' || v === 'mm') {
        let prevH = false, nextS = false;
        for (let j = i - 1; j >= 0; j--) { if (toks[j].t === 'dt') { prevH = /^h/.test(toks[j].v); break; } }
        for (let j = i + 1; j < toks.length; j++) { if (toks[j].t === 'dt') { nextS = /^s/.test(toks[j].v); break; } }
        if (prevH || nextS) { out += v === 'mm' ? String(M).padStart(2, '0') : String(M); continue; }
      }
      switch (v) {
        case 'yyyy': out += String(y).padStart(4, '0'); break; case 'yy': out += String(y % 100).padStart(2, '0'); break;
        case 'mmmmm': out += MONTHS_EN[mo][0]; break; case 'mmmm': out += MONTHS_EN[mo]; break; case 'mmm': out += MONTHS_EN[mo].slice(0, 3); break;
        case 'mm': out += String(mo + 1).padStart(2, '0'); break; case 'm': out += mo + 1; break;
        case 'dddd': out += DAYS_EN[wd]; break; case 'ddd': out += DAYS_EN[wd].slice(0, 3); break;
        case 'dd': out += String(da).padStart(2, '0'); break; case 'd': out += da; break;
        case 'hh': out += String(hasAmPm ? ((H % 12) || 12) : H).padStart(2, '0'); break; case 'h': out += hasAmPm ? ((H % 12) || 12) : H; break;
        case 'ss': out += String(S).padStart(2, '0'); break; case 's': out += S; break;
        default: out += v;
      }
    } else if (t.t === 'ampm') out += H >= 12 ? (/^a\/p$/i.test(t.v) ? 'P' : 'PM') : (/^a\/p$/i.test(t.v) ? 'A' : 'AM');
    else if (t.t === 'elapsed') { const tot = x * 24; out += t.v[0] === 'h' ? String(Math.floor(tot)).padStart(t.v.length, '0') : t.v[0] === 'm' ? String(Math.floor(x * 1440)).padStart(t.v.length, '0') : String(Math.floor(x * 86400)).padStart(t.v.length, '0'); }
    else if (t.t === 'lit') out += t.v;
    else if (t.t === 'dig' || t.t === 'sep') out += t.v;
  }
  return out;
}
const fmtCache = new Map();
/** يعيد {text, color?, align?} حسب رمز التنسيق. */
function formatValue(v, code, opts) {
  opts = opts || {};
  if (v === null || v === undefined) return { text: '' };
  if (isErr(v)) return { text: v.err, align: 'center' };
  if (typeof v === 'boolean') return { text: v ? 'TRUE' : 'FALSE', align: 'center' };
  if (!code || /^general$/i.test(code)) {
    if (typeof v === 'number') return { text: numToStr(v), align: 'right' };
    return { text: String(v) };
  }
  let parsed = fmtCache.get(code);
  if (!parsed) { parsed = splitSections(code).map(tokenizeFmt); fmtCache.set(code, parsed); }
  if (typeof v === 'string') {
    const sec = parsed[3] || (parsed.length === 1 && parsed[0].some(t => t.t === 'at') ? parsed[0] : null);
    if (sec) return { text: sec.map(t => t.t === 'at' ? v : t.t === 'lit' ? t.v : '').join('') };
    return { text: v };
  }
  let idx = 0, neg = false;
  if (parsed.length >= 2) { if (v < 0) { idx = 1; } else if (v === 0 && parsed.length >= 3) idx = 2; }
  else if (v < 0) neg = true;
  const sec = parsed[idx] || parsed[0];
  const isDate = sec.some(t => t.t === 'dt' || t.t === 'ampm' || t.t === 'elapsed') && !sec.some(t => t.t === 'dig');
  let text, color = null;
  const ct = sec.find(t => t.t === 'color'); if (ct) color = ct.v;
  if (isDate) {
    if (v < 0) return { text: '#####', align: 'right' };
    text = fmtDateSection(sec, v, opts.d1904);
  } else {
    text = fmtNumberSection(sec, v, neg);
  }
  return { text, color, align: 'right' };
}
const BUILTIN_NF = { 0: 'General', 1: '0', 2: '0.00', 3: '#,##0', 4: '#,##0.00', 9: '0%', 10: '0.00%', 11: '0.00E+00', 14: 'mm-dd-yy', 15: 'd-mmm-yy', 16: 'd-mmm', 17: 'mmm-yy', 18: 'h:mm AM/PM', 19: 'h:mm:ss AM/PM', 20: 'h:mm', 21: 'h:mm:ss', 22: 'm/d/yy h:mm', 37: '#,##0 ;(#,##0)', 38: '#,##0 ;[Red](#,##0)', 39: '#,##0.00;(#,##0.00)', 40: '#,##0.00;[Red](#,##0.00)', 45: 'mm:ss', 46: '[h]:mm:ss', 47: 'mmss.0', 48: '##0.0E+0', 49: '@' };

/** تحويل نص أدخله المستخدم إلى قيمة. يعيد {v, nf?} أو {f} للصيغة. */
function parseInput(text, d1904) {
  if (text === null || text === undefined) return { v: null };
  const s = String(text);
  if (s.length > 1 && s[0] === '=') return { f: s.slice(1) };
  if (s[0] === "'") return { v: s.slice(1) };
  const t = s.trim();
  if (t === '') return { v: null };
  if (/^TRUE$/i.test(t)) return { v: true };
  if (/^FALSE$/i.test(t)) return { v: false };
  if (/^[+-]?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?$/i.test(t)) return { v: parseFloat(t) };
  if (/^[+-]?\d{1,3}(,\d{3})+(\.\d+)?$/.test(t)) return { v: parseFloat(t.replace(/,/g, '')), nf: t.indexOf('.') >= 0 ? '#,##0.00' : '#,##0' };
  let m;
  if ((m = /^([+-]?(?:\d+\.?\d*|\.\d+))%$/.exec(t))) return { v: parseFloat(m[1]) / 100, nf: m[1].indexOf('.') >= 0 ? '0.00%' : '0%' };
  if ((m = /^(\d{4})[-/](\d{1,2})[-/](\d{1,2})$/.exec(t))) { const y = +m[1], mo = +m[2], d = +m[3]; if (mo >= 1 && mo <= 12 && d >= 1 && d <= 31) return { v: ymdToSerial(y, mo, d, d1904), nf: 'yyyy-mm-dd' }; }
  if ((m = /^(\d{1,2})[/-](\d{1,2})[/-](\d{4})$/.exec(t))) { const a = +m[1], b = +m[2]; const y = +m[3]; let d = a, mo = b; if (mo > 12 && d <= 12) { d = b; mo = a; } if (mo >= 1 && mo <= 12 && d >= 1 && d <= 31) return { v: ymdToSerial(y, mo, d, d1904), nf: 'dd/mm/yyyy' }; }
  if ((m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/.exec(t))) { const h = +m[1], mi = +m[2], se = +(m[3] || 0); if (h < 24 && mi < 60 && se < 60) return { v: (h * 3600 + mi * 60 + se) / 86400, nf: m[3] ? 'h:mm:ss' : 'h:mm' }; }
  return { v: s };
}

root.SheetEngine = { Engine, parse, colName, colIndex, parseA1, refStr, formatValue, parseInput, shiftFormula, adjustForStructure, rewriteFormula, isErr, ERR, BUILTIN_NF, numToStr, serialToDate, dateToSerial, ymdToSerial, cmpVals };
if (typeof module !== 'undefined') module.exports = root.SheetEngine;
})(typeof window !== 'undefined' ? window : globalThis);
