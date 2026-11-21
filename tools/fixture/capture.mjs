#!/usr/bin/env node
// 从原版 SillyTavern 录一份 golden fixture：把真实生成流程发出的请求体抓下来。
//
// 用法（先确保 ST 已在本机 8000 端口跑着）：
//   node tools/fixture/capture.mjs --out fixtures/expected/P0-5-prompt-basic.json
//
// 原理：用 Playwright 打开原版页面，把 window.fetch 换成拦截器，然后调用原版的
// Generate('normal')。请求体是原版自己组装的——我们一个字段都不碰，只抄。
// 这样得到的不是"我们认为原版会发什么"，而是"原版实际发了什么"。

import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const ROOT = path.resolve(new URL('../..', import.meta.url).pathname);
const ST_BASE = process.env.ST_BASE_URL || 'http://127.0.0.1:8000';

function arg(name, fallback = null) {
    const i = process.argv.indexOf(`--${name}`);
    return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const OUT = arg('out', 'fixtures/expected/capture.json');
const CHARACTER = arg('character', 'Seraphina');
const SEED = arg('seed', null);

// 每次录制都从「只有开场白」的干净聊天开始，否则上一轮生成的假回复会变成历史，
// 第二次录出来的 messages 就跟第一次不一样了（我踩过）。
const chatDir = path.join(ROOT, 'vendor/SillyTavern/data/default-user/chats', CHARACTER);
if (process.argv.indexOf('--keep-chat') === -1) {
    fs.rmSync(chatDir, { recursive: true, force: true });
}

// --seed：不直接写 JSONL（原版不认那种"手写聊天"），而是像用户一样把消息发进去：
// 设 textarea → Generate('normal')。助手回复由假 SSE 统一给 'ok'，所以每轮可复现。
// 多轮之后 capture 到的是最后一轮请求，历史里自然带上了前面几轮。
const SEEDED_TURNS = SEED
    ? JSON.parse(fs.readFileSync(path.resolve(ROOT, SEED), 'utf8')).userTurns
    : [];

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

const browser = await chromium.launch({
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'],
});
const page = await browser.newPage();

if (process.env.DEBUG) {
    page.on('console', m => console.log('[page]', m.type(), m.text()));
    page.on('pageerror', e => console.log('[pageerror]', e.message));
}

try {
    await page.goto(ST_BASE, { waitUntil: 'domcontentloaded' });

    // 有些版本会先给登录页；默认用户没有密码，点一下就进
    if (await page.locator('body.login').count()) {
        const user = page.locator('#userList .userSelect').last();
        if (await user.count()) await user.click();
    }

    await page.waitForFunction('document.getElementById("preloader") === null', null, { timeout: 180000 });

    const skipWorldInfo = process.argv.indexOf('--no-wi') !== -1;

    const result = await page.evaluate(async ({ character, skipWorldInfo, userTurns }) => {
        const S = await import('./script.js');
        const { getContext } = await import('./scripts/st-context.js');

        const readChars = () => getContext().characters || [];
        let chars = readChars();
        if (!chars.length && typeof S.getCharacters === 'function') {
            await S.getCharacters();
            chars = readChars();
        }
        if (!chars.length) {
            throw new Error('原版里没有角色卡。先放一张到 data/default-user/characters/，例如 Seraphina.png');
        }
        const idx = Math.max(0, chars.findIndex(c => c && c.name === character));
        if (getContext().characterId == null) {
            await S.selectCharacterById(String(idx));
        }
        // main_api 由页面上的 #main_api 下拉框驱动（没有导出的 setter），默认可能不是 openai。
        // 直接把 select 设成 openai 并触发 change，走原版自己的初始化路径。
        const apiSel = document.getElementById('main_api');
        if (apiSel && apiSel.value !== 'openai') {
            apiSel.value = 'openai';
            apiSel.dispatchEvent(new Event('change', { bubbles: true }));
            await new Promise(r => setTimeout(r, 500));
        }

        // Generate 在 online_status === 'no_connection' 时会直接早退（它以为后端没连上）。
        // 我们拦截了 fetch，本来就不会真发出去，所以这里只是把它"骗"成已连接。
        if (typeof S.setOnlineStatus === 'function') {
            S.setOnlineStatus('mock-model');
        }

        // 选中后聊天是异步加载的，等它真的有消息再继续
        for (let i = 0; i < 40 && !(getContext().chat?.length > 0); i++) {
            await new Promise(r => setTimeout(r, 250));
        }

        const captured = [];
        const allCalls = [];
        const originalFetch = window.fetch;
        window.fetch = async (input, init = {}) => {
            const url = typeof input === 'string' ? input : (input?.url ?? '');
            allCalls.push({ url, method: init?.method ?? 'GET' });
            if (url.includes('/api/backends/chat-completions/generate')) {
                captured.push({ url, body: JSON.parse(init.body) });
                const sse = 'data: {"choices":[{"delta":{"content":"ok"},"index":0}]}\n\ndata: [DONE]\n\n';
                return new Response(sse, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
            }
            return originalFetch(input, init);
        };

        let error = null;
        try {
            // skipWIAN：跳过世界书激活。P0 只需要"卡 → 提示词"这条主干，
            // 世界书属于 P1，混在同一个夹具里会让两边的失败分不清是谁的。
            const setTextarea = (text) => {
                const el = document.getElementById('send_textarea');
                el.value = text;
                el.dispatchEvent(new Event('input', { bubbles: true }));
            };

            for (const turn of userTurns) {
                setTextarea(turn);
                await S.Generate('normal', { skipWIAN: skipWorldInfo });
            }
            if (!userTurns.length) {
                await S.Generate('normal', { skipWIAN: skipWorldInfo });
            }
        } catch (e) {
            error = String(e?.message ?? e);
        } finally {
            window.fetch = originalFetch;
        }

        const context = getContext();
        return {
            captured,
            allCalls,
            error,
            mainApi: S.main_api,
            context: {
                name1: context.name1,
                name2: context.name2,
                characterId: context.characterId,
                chatId: context.chatId,
                chatLength: context.chat?.length ?? 0,
            },
        };
    }, { character: CHARACTER, skipWorldInfo, userTurns: SEEDED_TURNS });

    if (!result.captured.length) {
        throw new Error(`没抓到请求。Generate 的错误：${result.error ?? '（无）'}\n上下文：${JSON.stringify(result.context)}\n期间发生的请求：${JSON.stringify(result.allCalls)}`);
    }

    const last = result.captured.at(-1);
    const fixture = {
        _meta: {
            stVersion: readStVersion(),
            capturedAt: new Date().toISOString(),
            endpoint: last.url,
            how: 'playwright + window.fetch 拦截，调用原版 Generate("normal")',
            character: CHARACTER,
            context: result.context,
            generateError: result.error,
        },
        request: last.body,
    };

    const outPath = path.resolve(ROOT, OUT);
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    fs.writeFileSync(outPath, JSON.stringify(fixture, null, 2) + '\n');
    console.log(`OK  ${path.relative(ROOT, outPath)}`);
    console.log(`    messages: ${last.body.messages?.length ?? '?'} 条, body keys: ${Object.keys(last.body).join(', ')}`);
} finally {
    await browser.close();
}

function readStVersion() {
    try {
        return JSON.parse(fs.readFileSync(path.join(ROOT, 'vendor/SillyTavern/package.json'), 'utf8')).version;
    } catch {
        return null;
    }
}
