package zzz.service.forum.config;

/**
 * 批量操作配置参数
 *
 * @author nanno
 * @since 2025-09-10
 */
public class BatchConfig {
    
    /**
     * 批量处理大小，默认100条
     */
    public static final int BATCH_SIZE = 100;
    
    private BatchConfig() {
        // 工具类，禁止实例化
    }
}
