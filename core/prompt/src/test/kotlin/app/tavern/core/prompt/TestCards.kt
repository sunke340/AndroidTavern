package app.tavern.core.prompt

/**
 * 测试用的合成卡定义，与 `tools/fixture/make-test-card.py` 里的 CARD 逐字对应。
 *
 * 两边如果漂移，夹具测试会红——这是刻意的：合成卡改了就得重录夹具，不能让"测试里的卡"
 * 和"实际录夹具用的卡"悄悄变成两个东西。
 */
internal val syntheticCard = CharacterDefinition(
    name = "Synthetic",
    description = "DESC-ALPHA first line\nDESC-ALPHA second line mentions {{user}} and {{char}}",
    personality = "PERS-BETA",
    scenario = "SCEN-GAMMA",
    mesExample = "<START>\n{{user}}: EXAMPLE-DELTA-user line\n{{char}}: EXAMPLE-DELTA-char line",
)
