package io.ctyx.modpedia.ai;

/** 模型请求的输出预算；工具调用和面向玩家的回答使用不同上限。 */
public final class AiTokenBudget {
    /**
     * 首轮仍然只生成结构化工具参数，但不能用过小的上限把复杂实体、语言和
     * focus 参数截断。这个值只限制首轮输出，不限制搜索结果正文。
     */
    public static final int FIRST_TOOL_CALL = 1_536;
    /** GPT-5/o 系列会把推理 token 和工具参数共同计入 max_completion_tokens；
     * 预留足够的推理空间，避免首轮没有真正发出 search_knowledge。 */
    public static final int REASONING_FIRST_TOOL_CALL = 3_072;
    public static final int CONNECTION_TEST = 32;
    public static final int FAST_ANSWER = 1_280;
    public static final int STANDARD_ANSWER = 2_560;
    public static final int DEEP_ANSWER = 4_096;
    public static final int DEFAULT_ANSWER = STANDARD_ANSWER;

    private AiTokenBudget() {
    }

    public static int answerTokens(SearchIntensity intensity) {
        return switch (intensity == null ? SearchIntensity.STANDARD : intensity) {
            case FAST -> FAST_ANSWER;
            case STANDARD -> STANDARD_ANSWER;
            case DEEP -> DEEP_ANSWER;
            case CUSTOM -> DEEP_ANSWER;
        };
    }
}
