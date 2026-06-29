-- 初始化 PGVector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 验证扩展是否安装成功（会在日志中输出）
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        RAISE NOTICE 'PGVector extension installed successfully, version: %',
            (SELECT extversion FROM pg_extension WHERE extname = 'vector');
    ELSE
        RAISE WARNING 'PGVector extension installation failed!';
    END IF;
END $$;
