@echo off
rem ============================================================
rem  Start with a REAL OpenAI-compatible LLM gateway.
rem  Set these three env vars before running (or edit below).
rem    OPENAI_BASE_URL    e.g. https://api.siliconflow.cn/v1
rem    OPENAI_API_KEY     your key
rem    OPENAI_CHAT_MODEL  e.g. Qwen/Qwen2.5-7B-Instruct
rem
rem  Note: if your gateway has no /embeddings endpoint, keep
rem  --kb.llm.embedding-provider=mock so retrieval still works.
rem ============================================================
cd /d "%~dp0"
if "%OPENAI_BASE_URL%"=="" set OPENAI_BASE_URL=https://api.openai.com/v1
if "%OPENAI_CHAT_MODEL%"=="" set OPENAI_CHAT_MODEL=gpt-4o-mini
echo [INFO] gateway = %OPENAI_BASE_URL%
echo [INFO] model   = %OPENAI_CHAT_MODEL%
echo [INFO] key set = %OPENAI_API_KEY:~0,6%...
"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe" -Dfile.encoding=UTF-8 -jar target\ai-teaching-kb-1.0.0.jar --kb.llm.provider=openai --kb.llm.embedding-provider=mock
