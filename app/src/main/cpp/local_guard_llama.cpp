#include <jni.h>

#include "ggml-backend.h"
#include "llama.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int32_t n_ctx = 0;
};

std::once_flag g_backend_once;

void init_backend_once() {
    std::call_once(g_backend_once, [] {
        ggml_backend_load_all();
        llama_backend_init();
    });
}

void throw_illegal_state(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
    }
}

std::string jstring_to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return "";
    }
    const char * raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) {
        return "";
    }
    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

Engine * as_engine(jlong handle) {
    auto * engine = reinterpret_cast<Engine *>(static_cast<intptr_t>(handle));
    if (engine == nullptr || engine->model == nullptr || engine->ctx == nullptr || engine->vocab == nullptr) {
        throw std::runtime_error("Local llama engine is not initialized.");
    }
    return engine;
}

std::vector<llama_token> tokenize(Engine * engine, const std::string & prompt) {
    int32_t needed = -llama_tokenize(
            engine->vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            nullptr,
            0,
            true,
            true);
    if (needed <= 0) {
        throw std::runtime_error("Local llama failed to tokenize prompt.");
    }
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    int32_t count = llama_tokenize(
            engine->vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            true);
    if (count < 0) {
        throw std::runtime_error("Local llama prompt tokenization failed.");
    }
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

std::string token_to_piece(Engine * engine, llama_token token) {
    char small[128];
    int32_t written = llama_token_to_piece(engine->vocab, token, small, sizeof(small), 0, true);
    if (written >= 0) {
        return std::string(small, static_cast<size_t>(written));
    }
    int32_t needed = -written;
    if (needed <= 0) {
        throw std::runtime_error("Local llama failed to convert token.");
    }
    std::vector<char> buffer(static_cast<size_t>(needed));
    written = llama_token_to_piece(engine->vocab, token, buffer.data(), needed, 0, true);
    if (written < 0) {
        throw std::runtime_error("Local llama failed to convert token.");
    }
    return std::string(buffer.data(), static_cast<size_t>(written));
}

llama_sampler * create_sampler(Engine * engine, float temperature, const std::string & grammar, const std::string & grammar_root) {
    llama_sampler_chain_params params = llama_sampler_chain_default_params();
    params.no_perf = true;
    llama_sampler * sampler = llama_sampler_chain_init(params);
    if (sampler == nullptr) {
        throw std::runtime_error("Local llama failed to initialize sampler.");
    }
    if (!grammar.empty()) {
        llama_sampler * grammar_sampler = llama_sampler_init_grammar(
                engine->vocab,
                grammar.c_str(),
                grammar_root.empty() ? "root" : grammar_root.c_str());
        if (grammar_sampler == nullptr) {
            llama_sampler_free(sampler);
            throw std::runtime_error("Local llama failed to parse JSON grammar.");
        }
        llama_sampler_chain_add(sampler, grammar_sampler);
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    return sampler;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_androidbuilder_agent_LocalLlamaEngine_nativeCreate(
        JNIEnv * env,
        jclass,
        jstring model_path,
        jint context_size,
        jint threads) {
    try {
        init_backend_once();
        std::string path = jstring_to_string(env, model_path);
        if (path.empty()) {
            throw std::runtime_error("Local llama model path is empty.");
        }

        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.use_mmap = true;

        llama_model * model = llama_model_load_from_file(path.c_str(), model_params);
        if (model == nullptr) {
            throw std::runtime_error("Local llama failed to load GGUF model.");
        }

        int32_t safe_context = std::max(512, static_cast<int32_t>(context_size));
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = static_cast<uint32_t>(safe_context);
        ctx_params.n_batch = static_cast<uint32_t>(safe_context);
        ctx_params.n_ubatch = static_cast<uint32_t>(std::min(512, safe_context));
        ctx_params.no_perf = true;

        llama_context * ctx = llama_init_from_model(model, ctx_params);
        if (ctx == nullptr) {
            llama_model_free(model);
            throw std::runtime_error("Local llama failed to create context.");
        }

        int32_t safe_threads = std::max(1, static_cast<int32_t>(threads));
        llama_set_n_threads(ctx, safe_threads, safe_threads);

        auto * engine = new Engine();
        engine->model = model;
        engine->ctx = ctx;
        engine->vocab = llama_model_get_vocab(model);
        engine->n_ctx = static_cast<int32_t>(llama_n_ctx(ctx));
        if (engine->vocab == nullptr) {
            llama_free(ctx);
            llama_model_free(model);
            delete engine;
            throw std::runtime_error("Local llama model has no usable vocab.");
        }
        return static_cast<jlong>(reinterpret_cast<intptr_t>(engine));
    } catch (const std::exception & error) {
        throw_illegal_state(env, error.what());
        return 0L;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_androidbuilder_agent_LocalLlamaEngine_nativeGenerate(
        JNIEnv * env,
        jclass,
        jlong handle,
        jstring prompt_value,
        jint max_tokens,
        jfloat temperature,
        jstring grammar_value,
        jstring grammar_root_value) {
    llama_sampler * sampler = nullptr;
    try {
        Engine * engine = as_engine(handle);
        std::string prompt = jstring_to_string(env, prompt_value);
        std::string grammar = jstring_to_string(env, grammar_value);
        std::string grammar_root = jstring_to_string(env, grammar_root_value);
        std::vector<llama_token> prompt_tokens = tokenize(engine, prompt);
        if (prompt_tokens.empty()) {
            throw std::runtime_error("Local llama prompt is empty after tokenization.");
        }
        if (static_cast<int32_t>(prompt_tokens.size()) >= engine->n_ctx - 1) {
            throw std::runtime_error("Local llama prompt exceeds context window.");
        }

        int32_t predict = std::max(1, static_cast<int32_t>(max_tokens));
        predict = std::min(predict, engine->n_ctx - static_cast<int32_t>(prompt_tokens.size()) - 1);
        if (predict <= 0) {
            throw std::runtime_error("Local llama has no context left for output.");
        }

        llama_memory_clear(llama_get_memory(engine->ctx), true);
        sampler = create_sampler(engine, static_cast<float>(temperature), grammar, grammar_root);

        llama_batch batch = llama_batch_get_one(
                prompt_tokens.data(),
                static_cast<int32_t>(prompt_tokens.size()));
        if (llama_model_has_encoder(engine->model)) {
            if (llama_encode(engine->ctx, batch) != 0) {
                throw std::runtime_error("Local llama encoder evaluation failed.");
            }
            llama_token decoder_start = llama_model_decoder_start_token(engine->model);
            if (decoder_start == LLAMA_TOKEN_NULL) {
                decoder_start = llama_vocab_bos(engine->vocab);
            }
            batch = llama_batch_get_one(&decoder_start, 1);
        }

        std::string output;
        int32_t n_pos = 0;
        for (int32_t generated = 0; generated < predict && n_pos + batch.n_tokens < engine->n_ctx; generated++) {
            int32_t code = llama_decode(engine->ctx, batch);
            if (code != 0) {
                throw std::runtime_error("Local llama decode failed.");
            }
            n_pos += batch.n_tokens;

            llama_token token = llama_sampler_sample(sampler, engine->ctx, -1);
            if (llama_vocab_is_eog(engine->vocab, token)) {
                break;
            }
            output += token_to_piece(engine, token);
            batch = llama_batch_get_one(&token, 1);
        }

        llama_sampler_free(sampler);
        return env->NewStringUTF(output.c_str());
    } catch (const std::exception & error) {
        if (sampler != nullptr) {
            llama_sampler_free(sampler);
        }
        throw_illegal_state(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_androidbuilder_agent_LocalLlamaEngine_nativeClose(
        JNIEnv *,
        jclass,
        jlong handle) {
    auto * engine = reinterpret_cast<Engine *>(static_cast<intptr_t>(handle));
    if (engine == nullptr) {
        return;
    }
    if (engine->ctx != nullptr) {
        llama_free(engine->ctx);
    }
    if (engine->model != nullptr) {
        llama_model_free(engine->model);
    }
    delete engine;
}
