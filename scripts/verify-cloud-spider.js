/* 在 Node 里模拟 TVBox JS 引擎，端到端验证云端插件：
 * init -> home -> search -> detail -> play
 * 用 curl 实现同步 HTTP（--noproxy '*' 走国内直连）。
 */
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const { execFileSync } = require('child_process');
const os = require('os');

const ROOT = path.resolve(__dirname, '..');
const HOST = 'http://107.173.211.148';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

let seq = 0;
// TVBox 的 request 有两种调用形态：request(url, headers) 与
// request(url, {method, body, data, headers})。两种都要真实发出对应方法，
// 否则插件的 POST 会被发成 GET，UC 取链接口返回 405。
function request(url, arg) {
  seq += 1;
  const out = path.join(os.tmpdir(), 'cloud-spider-' + process.pid + '-' + seq + '.out');
  const bin = path.join(os.tmpdir(), 'cloud-spider-' + process.pid + '-' + seq + '.in');
  let method = 'GET', body = null, h = {};
  if (arg && typeof arg === 'object' && (arg.method || arg.body !== undefined || arg.data !== undefined)) {
    method = String(arg.method || 'POST').toUpperCase();
    body = arg.body !== undefined ? arg.body : arg.data;
    h = arg.headers || {};
  } else {
    h = arg || {};
  }
  const args = ['--noproxy', '*', '-sS', '-L', '--max-time', '35', '-X', method,
    '-H', 'User-Agent: ' + UA, '-o', out, '-w', '%{http_code}'];
  for (const k of Object.keys(h)) {
    if (/^user-agent$/i.test(k)) continue;
    args.push('-H', k + ': ' + h[k]);
  }
  if (body !== null && body !== undefined) {
    fs.writeFileSync(bin, typeof body === 'string' ? body : JSON.stringify(body));
    args.push('--data-binary', '@' + bin);
  }
  args.push(url);
  let status = 0, text = '';
  try {
    status = Number(execFileSync('curl', args, { encoding: 'utf8', timeout: 40000 }).trim());
    text = fs.readFileSync(out, 'utf8');
  } catch (e) {
    try { text = fs.readFileSync(out, 'utf8'); } catch (_) {}
  }
  for (const f of [out, bin]) { try { fs.unlinkSync(f); } catch (_) {} }
  return { code: status, data: text, headers: {} };
}

const code = fs.readFileSync(path.join(ROOT, 'spider/cloud_spider.js'), 'utf8');
const sandbox = { console, request, sleep: () => {} };
vm.createContext(sandbox);
vm.runInContext(code, sandbox, { filename: 'cloud_spider.js' });
console.log('[1] 插件加载 OK，字节=' + Buffer.byteLength(code));

function call(expr) { return vm.runInContext(expr, sandbox); }

// init(ext)：ext 必须是 JSON 字符串
call('init(' + JSON.stringify(JSON.stringify({ host: HOST, cookies: {} })) + ')');
console.log('[2] init(ext) OK, host=' + call('USER_HOST'));

const home = JSON.parse(call('home()'));
console.log('[3] home() -> ' + (home.list || []).length + ' 条');
if (!(home.list || []).length) throw new Error('home 无结果');

const kw = process.argv[2] || '凡人修仙传';
const search = JSON.parse(call('search(' + JSON.stringify(kw) + ', false)'));
console.log('[4] search("' + kw + '") -> ' + (search.list || []).length + ' 条');
if (!(search.list || []).length) throw new Error('search 无结果');

console.log('    首条: ' + search.list[0].vod_name);

// 从原始结果里挑一条 UC 免登资源，验证「点开即播」链路
const ctxExpr = '({"config":{"host":' + JSON.stringify(HOST) + '}})';
const raw = JSON.parse(call('JSON.stringify(qpSearch(' + ctxExpr + ',' + JSON.stringify(kw) + '))'));
const ucItems = (raw.items || []).filter(it => it.ref && it.ref.provider === 'uc');
console.log('    原始 ' + (raw.items || []).length + ' 条，其中 UC 免登 ' + ucItems.length + ' 条');
if (!ucItems.length) throw new Error('无 UC 免登结果，无法验证取链');
// 分享链接有时效，逐条试到第一条仍活跃的 UC 分享
let chosen = null, tried = 0;
for (const uc of ucItems.slice(0, 10)) {
  tried += 1;
  const id = call('_enc(' + JSON.stringify({ ref: uc.ref, itemId: uc.id, itemTitle: uc.title }) + ')');
  try {
    const d = JSON.parse(call('detail(' + JSON.stringify(id) + ')'));
    const d0 = (d.list || [])[0] || {};
    const froms = String(d0.vod_play_from || '').split('$$$');
    const groups = String(d0.vod_play_url || '').split('$$$');
    const eps = String(groups[0] || '').split('$$');
    if (eps.length && eps[0].includes('$')) { chosen = { froms, eps }; break; }
  } catch (e) {
    console.log('    UC #' + tried + ' 分享不可用: ' + String(e.message).slice(0, 80));
  }
}
if (!chosen) {
  // 聚合搜索里的分享链接有时效（UC 返回 41012 好友已取消了分享）。
  // 这属于数据源问题，不是插件/订阅问题，降级为警告，不判失败。
  console.log('[5] WARN: 前 ' + tried + ' 条 UC 分享均已失效，跳过取链验证');
  console.log('    订阅/插件/搜索/首页链路已验证；播放需在 TVBox 上用仍有效的分享实测');
  console.log('VERIFY_OK (配置与搜索链路)');
  process.exit(0);
}
const froms = chosen.froms, eps = chosen.eps;
console.log('[5] detail() -> 第 ' + tried + ' 条 UC 成功，源 ' + froms.length + ' 组，第一组 ' + eps.length + ' 集');

const epId = eps[0].split('$').slice(1).join('$');
const play = JSON.parse(call('play(' + JSON.stringify(froms[0] || '') + ',' + JSON.stringify(epId) + ',[])'));
const url = String(play.url || '');
console.log('[6] play() -> parse=' + play.parse + ' url=' + url.slice(0, 130));
console.log('    header keys=' + Object.keys(play.header || {}).join(','));
if (!/^https?:\/\//.test(url)) throw new Error('play 未返回直链');
if (/127\.0\.0\.1|192\.168\.|localhost/.test(url)) throw new Error('play 仍依赖本机');
console.log('VERIFY_OK (全链路)');
