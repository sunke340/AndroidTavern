#!/usr/bin/env node
// 录正则脚本夹具：把脚本塞进原版设置，然后**调用原版自己的 getRegexedString()**，抄下输入输出。
//
// 为什么不用一次生成请求来录：AI_OUTPUT / markdownOnly 那几档只影响显示，请求体里看不到；
// 直接调函数能把每种 placement × 显示/提示词组合都覆盖到，而且证据还是"原版算出来的"。
//
// 用法（ST 已在 8000 跑着）：
//   node tools/fixture/capture-regex.mjs --out fixtures/expected/P1-11-regex.json

import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const ROOT = path.resolve(new URL('../..', import.meta.url).pathname);
const ST_BASE = process.env.ST_BASE_URL || 'http://127.0.0.1:8000';

function arg(name, fallback = null) {
    const i = process.argv.indexOf(`--${name}`);
    return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const OUT = arg('out', 'fixtures/expected/P1-11-regex.json');

// 脚本集合：每条只测一个语义点，失败时一眼看出是哪条规则错了
const SCRIPTS = [
    {
        id: 'a', scriptName: 'global-replace', findRegex: '/alpha/g', replaceString: 'ALPHA-REPLACED',
        trimStrings: [], placement: [1], disabled: false, markdownOnly: false, promptOnly: false,
        runOnEdit: true, substituteRegex: 0, minDepth: null, maxDepth: null,
    },
    {
        id: 'b', scriptName: 'swap-groups', findRegex: '/(\\w+)-(\\w+)/', replaceString: '$2_$1',
        trimStrings: [], placement: [1], disabled: false, markdownOnly: false, promptOnly: false,
        runOnEdit: true, substituteRegex: 0, minDepth: null, maxDepth: null,
    },
    {
        id: 'c', scriptName: 'trim-strings', findRegex: '/\\[(.+?)\\]/g', replaceString: '<$1>',
        trimStrings: ['!'], placement: [1], disabled: false, markdownOnly: false, promptOnly: false,
        runOnEdit: true, substituteRegex: 0, minDepth: null, maxDepth: null,
    },
    {
        id: 'd', scriptName: 'match-token', findRegex: '/beta/g', replaceString: '[{{match}}]',
        trimStrings: [], placement: [1], disabled: false, markdownOnly: false, promptOnly: false,
        runOnEdit: true, substituteRegex: 0, minDepth: null, maxDepth: null,
    },
    {
        id: 'e', scriptName: 'ai-output', findRegex: '/gamma/g', replaceString: 'GAMMA-AI',
        trimStrings: [], placement: [2], disabled: false, markdownOnly: false, promptOnly: false,
        runOnEdit: true, substituteRegex: 0, minDepth: null, maxDepth: null,
    },
    {
        id: 'f', scriptName: 'prompt-only', findRegex: '/delta/g', replaceString: 'DELTA-PROMPT',
        trimStrings: [], placement: [1], disabled: false, markdownOnly: false, promptOnly: true,
        runOnEdit: true, substituteRegex: 0, minDepth: null, maxDepth: null,
    },
    {
        id: 'g', scriptName: 'first-only', findRegex: '/omega/', replaceString: 'OMEGA',
        trimStrings: [], placement: [1], disabled: false, markdownOnly: false, promptOnly: false,
        runOnEdit: true, substituteRegex: 0, minDepth: null, maxDepth: null,
    },
    {
        id: 'h', scriptName: 'disabled-one', findRegex: '/zeta/g', replaceString: 'ZETA',
        trimStrings: [], placement: [1], disabled: true, markdownOnly: false, promptOnly: false,
        runOnEdit: true, substituteRegex: 0, minDepth: null, maxDepth: null,
    },
];

const CASES = [
    { text: 'alpha beta gamma delta omega alpha', placement: 1, isMarkdown: false, isPrompt: false },
    { text: 'alpha beta gamma delta omega alpha', placement: 1, isMarkdown: false, isPrompt: true },
    { text: 'alpha beta gamma delta omega alpha', placement: 2, isMarkdown: false, isPrompt: false },
    { text: 'alpha beta gamma delta omega alpha', placement: 2, isMarkdown: true, isPrompt: false },
    { text: 'x-y and a-b', placement: 1, isMarkdown: false, isPrompt: false },
    { text: 'keep! [a!b] tail', placement: 1, isMarkdown: false, isPrompt: false },
    { text: 'zeta alpha', placement: 1, isMarkdown: false, isPrompt: false },
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

    const result = await page.evaluate(async ({ scripts, cases }) => {
        const ext = await import('./scripts/extensions.js');
        const engine = await import('./scripts/extensions/regex/engine.js');

        // 装脚本 + 保证 regex 扩展没被关掉（关掉时 getRegexedString 直接原样返回）
        ext.extension_settings.regex = scripts;
        ext.extension_settings.disabledExtensions = (ext.extension_settings.disabledExtensions || [])
            .filter(x => x !== 'regex');

        const out = [];
        for (const c of cases) {
            out.push({
                ...c,
                output: engine.getRegexedString(c.text, c.placement, {
                    isMarkdown: c.isMarkdown,
                    isPrompt: c.isPrompt,
                }),
            });
        }
        return out;
    }, { scripts: SCRIPTS, cases: CASES });

    const fixture = {
        _meta: {
            stVersion: JSON.parse(fs.readFileSync(path.join(ROOT, 'vendor/SillyTavern/package.json'), 'utf8')).version,
            capturedAt: new Date().toISOString(),
            how: 'playwright：把脚本塞进 extension_settings.regex，再调原版 getRegexedString() 抄输入输出',
            note: 'placement: 1=USER_INPUT 2=AI_OUTPUT；isMarkdown/isPrompt 决定 markdownOnly/promptOnly 脚本在哪一侧生效',
        },
        scripts: SCRIPTS,
        cases: result,
    };

    const outPath = path.resolve(ROOT, OUT);
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    fs.writeFileSync(outPath, JSON.stringify(fixture, null, 2) + '\n');
    console.log(`OK  ${path.relative(ROOT, outPath)}  (${result.length} 条用例)`);
    for (const c of result) console.log(`   [${c.placement}/${c.isMarkdown ? 'md' : c.isPrompt ? 'prompt' : 'raw'}] ${JSON.stringify(c.output)}`);
} finally {
    await browser.close();
}
