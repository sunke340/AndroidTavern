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
// --dump-chat：把原版**当前聊天数组**原样存一份（证明"新建聊天后第一条到底是什么"）。
// 只存内存里的 chat，不读磁盘——避免和别的录制互相干扰。
const DUMP_CHAT = arg('dump-chat', null);
// --wi-settings：世界书的全局开关（recursive / budget / depth / group scoring …），传 JSON。
// 用原版自己的 updateWorldInfoSettings() 设进去，不去点 DOM 复选框——那套选择器太脆。
const WI_SETTINGS = arg('wi-settings', null) ? JSON.parse(arg('wi-settings')) : null;
// --import-embedded-world：把卡内嵌世界书真的导入成原版的世界书文件并挂到这张卡上。
// 不做这一步，原版只在界面上弹一个"要不要导入？"的对话框，然后**什么都不注入**——
// 我第一次录 P1-2 就录出一份没有世界书的请求体（chat 里连条目都没有）。
const IMPORT_EMBEDDED = process.argv.indexOf('--import-embedded-world') !== -1;
// --select-world：把某个世界书挂到"全局激活"上（原版 updateWorldInfoSettings 的第二个参数）。
// 只导入不挂载的话，卡里没有 extensions.world，原版就当作"没挂书"，一条都不注入。
const SELECT_WORLD = arg('select-world', null);
// --prompt-order：直接改原版的 prompt_order（数组顺序＝发送顺序，enabled=false 就是不发）。
// 用来证明"预设里的 prompt_order 真的会被按序执行"，而不只是"我们照着默认顺序拼"。
// 传一个 JSON 数组：[{"identifier":"scenario","enabled":true}, ...]，没列到的按原相对顺序追在后面。
const PROMPT_ORDER = arg('prompt-order', null) ? JSON.parse(arg('prompt-order')) : null;

// --swipes N：录**消息 swipe**（P0-6）。先正常生成一条，再对最后一条助手消息 swipe N 次。
// 为什么要这一步：原版把"被换掉的那一条"存进 `swipes[]`、并把 `swipe_id` 指向当前这条；
// 不看原版落盘的结构就实现 swipe 存储 = 猜。
const SWIPES = Number(arg('swipes', '0'));
// --continue：再调一次 Generate('continue')（P0-6）。原版"继续"不新增消息，是把文本接在
// 最后一条后面；要确认的是"它发出去的请求与普通生成差在哪"，所以两份请求体都要留下来比。
const DO_CONTINUE = process.argv.indexOf('--continue') !== -1;

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

    const result = await page.evaluate(async ({ character, skipWorldInfo, userTurns, wiSettings, importEmbeddedWorld, selectWorld, promptOrder, swipes, doContinue }) => {
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
        const current = chars[Number(getContext().characterId)];
        if (!current || current.name !== character) {
            await S.selectCharacterById(String(idx));
        }

        // 世界书全局开关要在第一次 Generate 之前设好（模块级变量，设完即生效）
        const WI = await import('./scripts/world-info.js');
        if (importEmbeddedWorld) {
            await WI.importEmbeddedWorldInfo(true);
            await new Promise(r => setTimeout(r, 500));
        }
        if (selectWorld) {
            WI.updateWorldInfoSettings({}, [selectWorld]);
        }
        if (wiSettings) {
            WI.updateWorldInfoSettings(wiSettings, selectWorld ? [selectWorld] : undefined);
        }
        // main_api 由页面上的 #main_api 下拉框驱动（没有导出的 setter），默认可能不是 openai。
        // 直接把 select 设成 openai 并触发 change，走原版自己的初始化路径。
        const apiSel = document.getElementById('main_api');
        if (apiSel && apiSel.value !== 'openai') {
            apiSel.value = 'openai';
            apiSel.dispatchEvent(new Event('change', { bubbles: true }));
            await new Promise(r => setTimeout(r, 500));
        }

        // prompt_order：promptManager 是 openai.js 的导出（live binding），
        // 它读的就是 serviceSettings.prompt_order 里那个**活数组**，所以原地重排即可。
        let appliedOrder = null;
        if (promptOrder) {
            const AI = await import('./scripts/openai.js');
            const pm = AI.promptManager;
            if (!pm) throw new Error('promptManager 还没初始化（openai 后端没切过去？）');
            const entries = pm.getPromptOrderForCharacter(pm.activeCharacter);
            const byId = new Map(entries.map(e => [e.identifier, e]));
            const next = [];
            for (const item of promptOrder) {
                const entry = byId.get(item.identifier);
                if (!entry) throw new Error('未知的 prompt identifier: ' + item.identifier);
                entry.enabled = item.enabled !== false;
                next.push(entry);
            }
            for (const entry of entries) {
                if (!next.includes(entry)) next.push(entry);
            }
            entries.length = 0;
            entries.push(...next);
            appliedOrder = entries.map(e => ({ identifier: e.identifier, enabled: e.enabled }));
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
            // P0-6：先有一条助手消息，再对它连续 swipe —— 原版会把换掉的那条存进 swipes[]
            for (let i = 0; i < swipes; i++) {
                await S.Generate('swipe', { skipWIAN: skipWorldInfo });
            }
            if (doContinue) {
                await S.Generate('continue', { skipWIAN: skipWorldInfo });
            }
        } catch (e) {
            error = String(e?.message ?? e);
        } finally {
            window.fetch = originalFetch;
        }

        const context = getContext();
        // 世界书预算 = round(wi_budget% * maxPromptTokens)，必须记下来，否则测试只能猜上下文长度
        let maxPromptTokens = null;
        try { maxPromptTokens = typeof S.getMaxPromptTokens === 'function' ? S.getMaxPromptTokens() : null; } catch { }
        return {
            maxPromptTokens,
            appliedOrder,
            captured,
            allCalls,
            error,
            mainApi: S.main_api,
            chat: context.chat ?? [],
            context: {
                name1: context.name1,
                name2: context.name2,
                characterId: context.characterId,
                chatId: context.chatId,
                chatLength: context.chat?.length ?? 0,
            },
        };
    }, { character: CHARACTER, skipWorldInfo, userTurns: SEEDED_TURNS, wiSettings: WI_SETTINGS, importEmbeddedWorld: IMPORT_EMBEDDED, selectWorld: SELECT_WORLD, promptOrder: PROMPT_ORDER, swipes: SWIPES, doContinue: DO_CONTINUE });

    if (!result.captured.length) {
        throw new Error(`没抓到请求。Generate 的错误：${result.error ?? '（无）'}\n上下文：${JSON.stringify(result.context)}\n期间发生的请求：${JSON.stringify(result.allCalls)}`);
    }

    if (DUMP_CHAT) {
        const dumpPath = path.resolve(ROOT, DUMP_CHAT);
        fs.mkdirSync(path.dirname(dumpPath), { recursive: true });
        fs.writeFileSync(dumpPath, JSON.stringify(result.chat, null, 2) + '\n');
        console.log(`OK  ${path.relative(ROOT, dumpPath)}  (${result.chat.length} 条消息)`);
    }

    const last = result.captured.at(-1);
    const fixture = {
        _meta: {
            stVersion: readStVersion(),
            capturedAt: new Date().toISOString(),
            endpoint: last.url,
            how: 'playwright + window.fetch 拦截，调用原版 Generate("normal")' +
                (SWIPES > 0 ? ` + Generate("swipe") x${SWIPES}` : ''),
            swipes: SWIPES,
            continued: DO_CONTINUE,
            character: CHARACTER,
            world: SELECT_WORLD,
            wiSettings: WI_SETTINGS,
            maxPromptTokens: result.maxPromptTokens,
            promptOrder: result.appliedOrder,
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
