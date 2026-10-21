#!/usr/bin/env node
// Harvests the paths each screen of a crew profile registers, from the real screens (Phase 2 design, section 6).
//
//   node reliability/tools/harvest-crew.js [profile]        (default profile: "default")
//
// Reads reliability/crews/<profile>.screens.json, starts the packaged server (lib/crg-scoreboard.jar) in a temp
// directory, loads each screen in headless Chromium, records its Register frames, replaces the game ID with $GAME,
// and writes reliability/crews/<profile>.json. Needs Node and Playwright with Chromium:
//   npm install --no-save playwright && npx playwright install chromium
// Playwright is only needed for this tool, never to run the tests.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const net = require('net');
const http = require('http');
const { spawn, execSync } = require('child_process');

function loadPlaywright() {
  for (const m of ['playwright', '@playwright/test']) {
    try { return require(m); } catch (e) { /* try the next one */ }
  }
  console.error('harvest-crew: Playwright not found. Run: npm install --no-save playwright && npx playwright install chromium');
  process.exit(2);
}

const repo = path.resolve(__dirname, '..', '..');
const profile = process.argv[2] || 'default';
const crewDir = path.join(repo, 'reliability', 'crews');
const spec = JSON.parse(fs.readFileSync(path.join(crewDir, profile + '.screens.json'), 'utf8'));
const SETTLE_MS = 4000;

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); });
    s.on('error', reject);
  });
}

function copyDir(src, dst, skip) {
  fs.mkdirSync(dst, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    if (skip && e.name === skip) continue;
    const s = path.join(src, e.name), d = path.join(dst, e.name);
    if (e.isDirectory()) copyDir(s, d); else fs.copyFileSync(s, d);
  }
}

function answers(port) {
  return new Promise(resolve => {
    const req = http.get({ host: '127.0.0.1', port, path: '/urls/', timeout: 2000 }, r => { r.resume(); resolve(r.statusCode === 200); });
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

function killTree(child) {
  if (!child || child.exitCode !== null) return;
  if (process.platform === 'win32') {
    try { execSync('taskkill /pid ' + child.pid + ' /T /F', { stdio: 'ignore' }); } catch (e) { /* already gone */ }
  } else {
    try { process.kill(-child.pid, 'SIGKILL'); } catch (e) { try { child.kill('SIGKILL'); } catch (e2) { /* gone */ } }
  }
}

(async () => {
  const { chromium } = loadPlaywright();
  const jar = path.join(repo, 'lib', 'crg-scoreboard.jar');
  if (!fs.existsSync(jar)) { console.error('harvest-crew: ' + jar + ' not found; build the product first (ant)'); process.exit(2); }
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'crg-harvest-'));
  copyDir(path.join(repo, 'config'), path.join(dir, 'config'), 'autosave');
  copyDir(path.join(repo, 'html'), path.join(dir, 'html'));
  const port = await freePort();
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
  const server = spawn(java, ['-jar', jar, '--port=' + port, '--host=127.0.0.1', '--import='],
    { cwd: dir, detached: process.platform !== 'win32', stdio: ['ignore', fs.openSync(path.join(dir, 'server.log'), 'a'), fs.openSync(path.join(dir, 'server.log'), 'a')] });
  const cleanup = () => { killTree(server); };
  process.on('exit', cleanup);
  process.on('SIGINT', () => process.exit(130));
  let browser;
  try {
    let up = false;
    for (let i = 0; i < 300 && !up; i++) { up = await answers(port); if (!up) await new Promise(r => setTimeout(r, 200)); }
    if (!up) throw new Error('server did not answer on port ' + port + '; see ' + path.join(dir, 'server.log'));

    browser = await chromium.launch();
    const ctx = await browser.newContext();
    // Learn the current game ID the way index.html does.
    let game = null;
    const p0 = await ctx.newPage();
    p0.on('websocket', ws => ws.on('framereceived', f => {
      const m = /"ScoreBoard\.CurrentGame\.Game"\s*:\s*"([^"]+)"/.exec(f.payload); if (m) game = m[1];
    }));
    await p0.goto(`http://127.0.0.1:${port}/`);
    for (let i = 0; i < 100 && !game; i++) await p0.waitForTimeout(100);
    await p0.close();
    if (!game) throw new Error('could not learn the current game ID');

    const devices = {};
    for (const [name, d] of Object.entries(spec.devices)) {
      const page = await ctx.newPage();
      const regs = [];
      page.on('websocket', ws => ws.on('framesent', f => {
        try { const j = JSON.parse(f.payload); if (j.action === 'Register') regs.push(...(j.paths || [])); } catch (e) { /* not JSON */ }
      }));
      await page.goto(`http://127.0.0.1:${port}${d.source.split('$GAME').join(game)}`);
      await page.waitForTimeout(SETTLE_MS);
      await page.close();
      const paths = [...new Set(regs.map(p => p.split(game).join('$GAME')))].sort();
      if (paths.length === 0) throw new Error(name + ' (' + d.source + ') registered no paths');
      devices[name] = { source: d.source, platform: 'trace', role: d.role, paths };
      console.log(`${name.padEnd(8)} ${String(paths.length).padStart(4)} paths  ${d.source}`);
    }
    let crgVersion = 'unknown';
    try { crgVersion = execSync('git describe --tags --always --dirty', { cwd: repo }).toString().trim(); } catch (e) { /* no git */ }
    const out = {
      profile: spec.profile || profile,
      generatedBy: 'reliability/tools/harvest-crew.js',
      crgVersion,
      notes: spec.notes,
      devices,
    };
    fs.writeFileSync(path.join(crewDir, profile + '.json'), JSON.stringify(out, null, 1) + '\n');
    console.log('wrote ' + path.join('reliability', 'crews', profile + '.json'));
  } finally {
    if (browser) await browser.close();
    cleanup();
    try { fs.rmSync(dir, { recursive: true, force: true }); } catch (e) { /* Windows may hold files briefly */ }
  }
})().catch(e => { console.error('harvest-crew: ' + (e && e.stack || e)); process.exit(1); });
