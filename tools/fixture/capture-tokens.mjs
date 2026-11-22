#!/usr/bin/env node
// 录 token 计数夹具：让**原版页面**自己算 token，我们把 (文本, 计数) 抄下来。
//
// 为什么不在 Node 里直接跑 tiktoken：原版用的是它自己打包进前端的 gpt-tokenizer，
// 版本和参数都可能跟我们要引的库不同。夹具必须是"原版实际算出来的数"，否则断言的是巧合。
//
// 用法（ST 已在 8000 跑着）：
//   node tools/fixture/capture-tokens.mjs --out fixtures/expected/P1-6-tokens.json
//
// 文本集合固定写在下面，覆盖：英文短句、中文、Markdown、世界书那种长条（x 重复串）。

import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const ROOT = path.resolve(new URL('../..', import.meta.url).pathname);
const ST_BASE = process.env.ST_BASE_URL || 'http://127.0.0.1:8000';

function arg(name, fallback = null) {
    const i = process.argv.indexOf(`--${name}`);
    return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const OUT = arg('out', 'fixtures/expected/P1-6-tokens.json');
// 预算夹具里的条目就是长这样："MARK-BUDGET-N " + 360 个 x
const repeated = 'x'.repeat(360);

const TEXTS = [
    'hello world',
    'Write Seraphina\'s next reply in a fictional chat between Seraphina and User.',
    '你好，旅人。很高兴看到你醒来。',
    '## 标题\n\n- 列表项一\n- 列表项二\n\n**粗体** 与 `代码`。',
    `MARK-BUDGET-0 ${repeated}`,
    `MARK-BUDGET-1 ${repeated}`,
    repeated,
    '*Seraphina smiles softly, turning back toward you as she hums in thought.* "Well, as guardian of this glade, I possess certain gifts - healing, protection, nature magic and the like."',
    '1234567890 1234567890 1234567890',
    'a\nb\nc\nd\ne',
];

async function loadPlaywright() {
    const candidates = [
        path.join(ROOT, 'vendor/SillyTavern/tests/node_modules/playwright/index.mjs'),
        path.join(ROOT, 'vendor/SillyTavern/tests/node_modules/@playwright/test/index.mjs'),
    ];
    for (const c of candidates) {
        if (fs.existsSync(c)) return await import(pathToFileURL(c).href);
    }
    throw new Error('没找到 playwright。先跑：cd vendor/SillyTavern/tests && npm ci');
}

const { chromium } = await loadPlaywright();
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'] });
const page = await browser.newPage();

try {
    await page.goto(ST_BASE, { waitUntil: 'domcontentloaded' });
    if (await page.locator('body.login').count()) {
        const user = page.locator('#userList .userSelect').last();
        if (await user.count()) await user.click();
    }
    await page.waitForFunction('document.getElementById("preloader") === null', null, { timeout: 180000 });

    const result = await page.evaluate(async (texts) => {
        const S = await import('./script.js');
        // 词表是按 main_api/模型挑的：页面默认可能停在 koboldhorde 上（它走 gpt2 那套），
        // 那样录出来的计数与 gpt-4-turbo 的 cl100k 对不上。先切到 openai。
        const apiSel = document.getElementById('main_api');
        if (apiSel && apiSel.value !== 'openai') {
            apiSel.value = 'openai';
            apiSel.dispatchEvent(new Event('change', { bubbles: true }));
            await new Promise(r => setTimeout(r, 800));
        }
        const T = await import('./scripts/tokenizers.js');
        const out = [];
        for (const text of texts) {
            out.push({ text, count: await T.getTokenCountAsync(text) });
        }
        return { model: S.oai_settings?.openai_model ?? null, mainApi: S.main_api, cases: out };
    }, TEXTS);

    const fixture = {
        _meta: {
            stVersion: JSON.parse(fs.readFileSync(path.join(ROOT, 'vendor/SillyTavern/package.json'), 'utf8')).version,
            capturedAt: new Date().toISOString(),
            how: 'playwright：在页面里调用原版 getTokenCountAsync()，抄下 (文本, 计数)',
            model: result.model,
            mainApi: result.mainApi,
            note: 'gpt-4-turbo 走 cl100k_base；换模型重录要连断言一起改',
        },
        cases: result.cases,
    };

    const outPath = path.resolve(ROOT, OUT);
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    fs.writeFileSync(outPath, JSON.stringify(fixture, null, 2) + '\n');
    console.log(`OK  ${path.relative(ROOT, outPath)}  (${result.cases.length} 条, model=${result.model})`);
} finally {
    await browser.close();
}
