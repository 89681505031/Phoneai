#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
llama = root / "third_party" / "llama.cpp"
if not llama.exists():
    raise SystemExit("llama.cpp not found; run setup_llama.sh first")


def replace_once(path: Path, old: str, new: str):
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"Patch anchor not found in {path}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

# Public API: GPU offload + backend device inventory.
api = llama / "examples/llama.android/lib/src/main/java/com/arm/aichat/InferenceEngine.kt"
replace_once(api,
'''    suspend fun loadModel(pathToModel: String)''',
'''    suspend fun loadModel(pathToModel: String, gpuLayers: Int = 0)''')
replace_once(api,
'''    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String''',
'''    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String\n\n    /** Backend devices currently visible to ggml (CPU/GPU/accelerators). */\n    fun backendDevices(): String''')

impl = llama / "examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"
replace_once(impl,
'''    private external fun load(modelPath: String): Int''',
'''    private external fun load(modelPath: String, gpuLayers: Int): Int''')
replace_once(impl,
'''    private external fun systemInfo(): String''',
'''    private external fun systemInfo(): String\n\n    @FastNative\n    private external fun backendDevicesNative(): String''')
replace_once(impl,
'''    private var _readyForSystemPrompt = false\n    @Volatile''',
'''    private var _readyForSystemPrompt = false\n    private var _nativeModelLoaded = false\n    @Volatile''')
replace_once(impl,
'''    override suspend fun loadModel(pathToModel: String) =\n        withContext(llamaDispatcher) {''',
'''    override suspend fun loadModel(pathToModel: String, gpuLayers: Int) =\n        withContext(llamaDispatcher) {''')
replace_once(impl,
'''                load(pathToModel).let {\n                    // TODO-han.yin: find a better way to pass other error codes\n                    if (it != 0) throw UnsupportedArchitectureException()\n                }\n                prepare().let {''',
'''                load(pathToModel, gpuLayers.coerceAtLeast(0)).let {\n                    // TODO-han.yin: find a better way to pass other error codes\n                    if (it != 0) throw UnsupportedArchitectureException()\n                    _nativeModelLoaded = true\n                }\n                prepare().let {''')
replace_once(impl,
'''            } catch (e: Exception) {\n                Log.e(TAG, (e.message ?: "Error loading model") + "\\n" + pathToModel, e)\n                _state.value = InferenceEngine.State.Error(e)\n                throw e\n            }''',
'''            } catch (e: Exception) {\n                Log.e(TAG, (e.message ?: "Error loading model") + "\\n" + pathToModel, e)\n                if (_nativeModelLoaded) {\n                    runCatching { unload() }\n                    _nativeModelLoaded = false\n                }\n                _state.value = InferenceEngine.State.Error(e)\n                throw e\n            }''')
replace_once(impl,
'''    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =''',
'''    override fun backendDevices(): String = backendDevicesNative()\n\n    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =''')
replace_once(impl,
'''                    unload()\n\n                    _state.value = InferenceEngine.State.Initialized''',
'''                    unload()\n                    _nativeModelLoaded = false\n\n                    _state.value = InferenceEngine.State.Initialized''')

cpp = llama / "examples/llama.android/lib/src/main/cpp/ai_chat.cpp"
replace_once(cpp,
'''Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {\n    llama_model_params model_params = llama_model_default_params();''',
'''Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path, jint jgpu_layers) {\n    llama_model_params model_params = llama_model_default_params();\n    model_params.n_gpu_layers = std::max(0, (int) jgpu_layers);\n    LOGi("%s: requested GPU layers: %d", __func__, model_params.n_gpu_layers);''')
# Add device inventory JNI just before systemInfo JNI.
anchor = '''extern "C"\nJNIEXPORT jstring JNICALL\nJava_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {'''
if "InferenceEngineImpl_backendDevicesNative" not in cpp.read_text(encoding="utf-8"):
    block = '''extern "C"\nJNIEXPORT jstring JNICALL\nJava_com_arm_aichat_internal_InferenceEngineImpl_backendDevicesNative(JNIEnv *env, jobject /*unused*/) {\n    std::stringstream out;\n    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {\n        auto *dev = ggml_backend_dev_get(i);\n        auto *reg = ggml_backend_dev_backend_reg(dev);\n        const auto type = ggml_backend_dev_type(dev);\n        const char *type_name = type == GGML_BACKEND_DEVICE_TYPE_GPU ? "GPU" :\n                                type == GGML_BACKEND_DEVICE_TYPE_IGPU ? "IGPU" :\n                                type == GGML_BACKEND_DEVICE_TYPE_ACCEL ? "ACCEL" :\n                                type == GGML_BACKEND_DEVICE_TYPE_META ? "META" : "CPU";\n        if (i) out << "\\n";\n        out << ggml_backend_reg_name(reg) << "|"\n            << ggml_backend_dev_name(dev) << "|"\n            << ggml_backend_dev_description(dev) << "|"\n            << type_name;\n    }\n    return env->NewStringUTF(out.str().c_str());\n}\n\n'''
    replace_once(cpp, anchor, block + anchor)

# Make OpenCL an opt-in Gradle build feature so ordinary CPU builds still work.
gradle = llama / "examples/llama.android/lib/build.gradle.kts"
replace_once(gradle,
'''plugins {\n    alias(libs.plugins.android.library)\n    alias(libs.plugins.jetbrains.kotlin.android)\n}\n''',
'''plugins {\n    alias(libs.plugins.android.library)\n    alias(libs.plugins.jetbrains.kotlin.android)\n}\n\nval phoneAiOpenCl = System.getenv("PHONEAI_OPENCL") == "1" ||\n    providers.gradleProperty("phoneaiOpenCl").orNull == "true"\n''')
replace_once(gradle,
'''        ndk {\n             abiFilters += listOf("arm64-v8a", "x86_64")\n        }''',
'''        ndk {\n             abiFilters += if (phoneAiOpenCl) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64")\n        }''')
replace_once(gradle,
'''                arguments += "-DGGML_LLAMAFILE=OFF"''',
'''                arguments += "-DGGML_LLAMAFILE=OFF"\n                if (phoneAiOpenCl) {\n                    arguments += "-DGGML_OPENCL=ON"\n                    arguments += "-DGGML_OPENCL_EMBED_KERNELS=ON"\n                    arguments += "-DGGML_OPENCL_USE_ADRENO_KERNELS=ON"\n                }''')

print("PhoneAI Android patches applied")
