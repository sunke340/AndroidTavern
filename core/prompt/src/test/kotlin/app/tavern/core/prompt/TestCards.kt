package app.tavern.core.prompt

import app.tavern.core.card.CharacterCard

/**
 * 测试用的合成卡，直接从 `fixtures/cards/P0-5-synthetic.png` 读出来。
 *
 * 关键点：**不复制字段到测试里**。之前那版把卡字段抄在 Kotlin 里，等于让"测试里的卡"和
 * "录夹具用的卡"各维护一份，迟早漂移。现在卡只有一份真相：那张 PNG。
 */
internal val syntheticCard: CharacterCard = Fixtures.card("P0-5-synthetic.png")
