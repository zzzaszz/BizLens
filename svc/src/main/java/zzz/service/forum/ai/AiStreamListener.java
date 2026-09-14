package zzz.service.forum.ai;

/**
 * 流式输出回调：模型增量逐段推送给监听者。
 */
public interface AiStreamListener {

    /** 收到一段增量文本 */
    void onToken(String delta);

    /** 出错 */
    void onError(Throwable t);

    /** 完成 */
    void onDone();
}
