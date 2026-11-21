# 夜间执行计划（2026-11-22 夜 → 明早验收）

用户睡前授权：按此计划连续执行，早上起床验收。**只做清单内的，不顺手扩张。**

## 起点状态（2026-11-22 02:45）

- M0 已完成并在真机验证：[device-verification.md](dev/device-verification.md)
- `:core:{model,card-format,prompt,ai}` 共 20 个单测；`:app` 能装、能连 provider、能流式、能切卡、能看请求
- 未做：持久化、卡片导入、Key 存储、签名固定、世界书

## 三个目标（按此顺序，做不完就停）

### G1 · 基础收尾（必达，约 1h）

1. 固定 debug 签名：debug keystore 入库（用户已批准），`build.gradle.kts` 指定 `signingConfig`
2. CI 注入递增 `versionCode`（用 run number），避免覆盖安装时提示降级
3. 保存回退点：把当前可用 APK 发成 `apk-m0` release
4. release 备注写 commit sha + 构建时间

验收：手机上可直接覆盖安装，不卸载、不报签名不一致。

### G2 · 带自己的卡（第二种，价值最高）

5. SAF 导入卡片：系统文件选择器选 PNG（`ACTION_OPEN_DOCUMENT`）→ `PngCard.read` → 复制进应用私有目录
6. 卡片列表：列出内置卡 + 已导入卡，可切换；记住上次选择

验收：从手机里选一张自己的 PNG 卡，导进来能聊，回复像卡里的人。

### G3 · 记录留存（M1 地基，风险最高）

7. `:core:chat-format`：JSONL 读写（首行 `chat_metadata`，逐行消息含 `swipes`），本机可测
8. 用原版录一份带多轮 + swipe 的聊天做 fixture
9. `:data`（Room）：会话表 + 消息表；App 重启后恢复
10. 会话列表：新建 / 切换 / 删除

验收：聊三轮 → 杀进程 → 重开，记录还在。

### 顺手（有余力）

11. Key 存 EncryptedSharedPreferences
12. 停止 = 保留已收到的半截内容（现在会丢）
13. CI 冒烟检查：`aapt2 dump badging` 验证 minSdk / INTERNET / 签名

## 纪律

- `main` 必须始终可构建；每步小提交
- 涉及兼容行为先有夹具（铁律 #2）
- 每个目标完成且 CI 绿 → 打 tag `night-g1` / `night-g2` / `night-g3`
- **06:30 硬停**：保证 main 绿 + APK 可装 + 写早上测试说明
- 不碰 `vendor/`，不扩 P3

## 明早验收清单（按实际做到哪档给）

**G1**：覆盖安装（不卸载）→ 填 key → 发一句 → 关 App → 重开看 Key 还在不在

**G2**：导入自己的 PNG 卡 → 聊 → 回复是否贴合卡设

**G3**：聊三轮 → 杀进程 → 重开 → 记录/会话列表是否在

## 交付

每完成一档：APK 直发 QQ（`c2c:<openid>`，见 `.secrets/personal.md`），并附这一档的验收要点。
若某档做不完：明确说"没做到"，给出下一档能测的范围，不假装完成。
