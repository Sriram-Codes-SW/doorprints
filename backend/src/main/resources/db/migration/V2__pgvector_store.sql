-- Optional AI "Ask my house hunt" (RAG) storage. See docs/ai/ai-design.md.
--
-- The table layout mirrors what Spring AI 2.0.1's PgVectorStore reads and writes
-- (PgVectorStore#createObjectsInDatabase): id uuid PK, content text, metadata json,
-- embedding vector(N) plus an HNSW cosine index named spring_ai_vector_index.
-- One row per house; id = house id so re-indexing is an upsert (ON CONFLICT (id)).
--
-- The dimension is FIXED at 768 here and must equal AI_EMBEDDING_DIMENSIONS
-- (gemini-embedding-2 / gemini-embedding-001 with output dimensionality 768, or
-- Ollama nomic-embed-text which is natively 768).
--
-- Environments without the pgvector extension (e.g. a plain PostGIS image) must keep
-- working with AI disabled, so everything is guarded: if "vector" is not available this
-- migration is a no-op. If you install pgvector later, either set
-- AI_VECTOR_INIT_SCHEMA=true once or run the statements below by hand.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
        EXECUTE 'CREATE EXTENSION IF NOT EXISTS vector';
        EXECUTE $ddl$
            CREATE TABLE IF NOT EXISTS vector_store (
                id        uuid PRIMARY KEY,
                content   text,
                metadata  json,
                embedding vector(768)
            )
        $ddl$;
        EXECUTE 'CREATE INDEX IF NOT EXISTS spring_ai_vector_index ON vector_store USING hnsw (embedding vector_cosine_ops)';
    ELSE
        RAISE NOTICE 'pgvector extension not available: skipping vector_store (AI RAG features need it)';
    END IF;
END
$$;
