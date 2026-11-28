const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

const source = fs.readFileSync('transfer_desktop_ui.py', 'utf8');
const start = source.indexOf('async function decodeNovelText(');
const end = source.indexOf('async function transferRun(', start);
assert(start >= 0 && end > start);
const context = vm.createContext({TextDecoder, Uint8Array, Error});
vm.runInContext(source.slice(start, end), context);
const file = bytes => ({arrayBuffer: async () => Uint8Array.from(bytes).buffer});

(async () => {
  assert.equal(await context.decodeNovelText(file([0xef,0xbb,0xbf,0xe4,0xb8,0xad,0xe6,0x96,0x87])), '中文');
  assert.equal(await context.decodeNovelText(file([0xd6,0xd0,0xce,0xc4])), '中文');
  assert.equal(await context.decodeNovelText(file([0xff,0xfe,0x2d,0x4e,0x87,0x65])), '中文');
  assert.equal(await context.decodeNovelText(file([0xd6,0xd0,0xce,0xc4]), 'gb18030'), '中文');
  console.log('Desktop novel text decoding smoke tests passed');
})().catch(error => {console.error(error); process.exitCode = 1});
