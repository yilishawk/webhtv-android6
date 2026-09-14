/*
 * libvulkan.so — a stub Vulkan loader for Android 6.
 *
 * WHY THIS EXISTS
 * ---------------
 * libmpv.so lists libvulkan.so in its DT_NEEDED, because it is built with libplacebo's
 * Vulkan backend enabled. Vulkan arrived in Android 7.0 (API 24): the NDK's API-23 stub
 * directory contains 16 libraries and no libvulkan.so, while API 24 adds it. On Android 6
 * the loader therefore fails before it ever looks at a symbol:
 *
 *     dlopen failed: library "libvulkan.so" not found
 *
 * This is a different failure from the one libc_shim.c handles. No amount of symbol
 * shimming can supply a library that does not exist — the symbol shim gives symbols, not
 * files. The fix is one level up: provide the file.
 *
 * WHY NOT JUST DROP IN A REAL VULKAN LOADER
 * -----------------------------------------
 * Because on this device there is no driver to load, and on every other device we must not
 * get in the way. See the placement note below.
 *
 * WHY NOT SHIP THE NDK'S OWN libvulkan.so STUB
 * --------------------------------------------
 * The NDK does ship an API-24 libvulkan.so stub, but those stubs exist for the *linker*:
 * their entry points are empty. An empty vkCreateInstance() returns whatever happens to be
 * in r0 — and 0 is VK_SUCCESS. mpv would conclude it had a working Vulkan device and walk
 * straight into a stack of functions that do nothing. A stub that reports failure is far
 * safer than one that accidentally reports success.
 *
 * WHAT THIS DOES
 * --------------
 * Behaves like a real Vulkan loader with no driver installed. That is a state real devices
 * reach all the time (no Vulkan driver, or the loader present but with no ICD), so it is a
 * path libplacebo already handles: vkCreateInstance returns VK_ERROR_INCOMPATIBLE_DRIVER
 * and the caller falls back to OpenGL. Claiming success would be the dangerous choice.
 *
 * The 63 vk* entry points below are not a guess: they are exactly the undefined vk*
 * references in libmpv.so, extracted with llvm-nm and identical on armeabi-v7a and
 * arm64-v8a (apk-check/shimtest/vk-syms-*.txt). RTLD_NOW means every one of them must
 * resolve at dlopen time even though almost none will ever be called.
 *
 * Note what is NOT in that list: vkCreateInstance. libplacebo reaches it through
 * vkGetInstanceProcAddr, which is the standard loader pattern — so vkGetInstanceProcAddr is
 * the entry point that actually has to behave correctly, and it is implemented by hand
 * below.
 *
 * ⛔ WHERE THIS FILE MUST NOT GO: the APK's lib/<abi>/ directory. A library there is part
 * of the app's native-library search path, so on a device that HAS Vulkan it would shadow
 * the system's real libvulkan.so and break Vulkan for that user. It ships in the mpv-libs
 * asset bundle instead and is preloaded by absolute path, and only when SDK_INT < 24.
 * Because libmpv.so names it in DT_NEEDED, bionic resolves it inside that dependency
 * closure — no -Wl,-z,global is needed (that flag is for symbols arriving from no declared
 * dependency at all, which is the situation libc_shim.c is in).
 *
 * The logcat probe below exists to answer one question the static analysis cannot: did mpv
 * actually touch Vulkan on this device? On API 23 the app never asks for it — MPVLib's
 * isDeviceVulkan13Capable() requires SDK_INT >= 33 and the log says
 * "render requested=opengl" — so the expectation is that this library is seen by the
 * linker and never called. "vulkan stub touched" in the next log confirms or refutes that.
 */

#include <android/log.h>
#include <stdint.h>
#include <string.h>

#define LOG_TAG "vulkan-stub"

/* Minimal Vulkan vocabulary. The values are fixed by the Vulkan ABI (vulkan_core.h):
 * VK_SUCCESS = 0, VK_ERROR_INITIALIZATION_FAILED = -3, VK_ERROR_INCOMPATIBLE_DRIVER = -9.
 * VK_API_VERSION_1_0 packs major/minor/patch into 31 bits: (1 << 22) | (0 << 12) | 0. */
typedef int32_t  VkResult;
typedef uint32_t VkFlags;
typedef uint32_t VkBool32;

#define VK_SUCCESS                       0
#define VK_ERROR_INITIALIZATION_FAILED  (-3)
#define VK_ERROR_INCOMPATIBLE_DRIVER    (-9)
#define VK_API_VERSION_1_0              0x00400000u

typedef void (*PFN_vkVoidFunction)(void);

/* Opaque handles: never dereferenced, only passed back to the caller. */
typedef struct VkInstance_T*       VkInstance;
typedef struct VkPhysicalDevice_T* VkPhysicalDevice;
typedef struct VkDevice_T*         VkDevice;

/* Opaque structs: we accept pointers to them and read nothing. Declaring them incomplete
 * is deliberate — it makes it impossible to accidentally depend on a field layout. */
typedef struct VkAllocationCallbacks VkAllocationCallbacks;
typedef struct VkInstanceCreateInfo  VkInstanceCreateInfo;
typedef struct VkExtensionProperties VkExtensionProperties;
typedef struct VkLayerProperties     VkLayerProperties;

/* ------------------------------------------------------------------ */
/* The one behaviour that matters                                      */
/* ------------------------------------------------------------------ */

/*
 * There is no driver, so there is no instance. VK_ERROR_INCOMPATIBLE_DRIVER is what a real
 * loader returns in exactly this situation, and it is what makes libplacebo abandon Vulkan
 * and fall back to GL instead of trying to use a device that is not there.
 *
 * *pInstance is cleared as well as the error returned: a caller that checks only the error
 * and then uses the instance would otherwise be handed uninitialised stack.
 */
VkResult vkCreateInstance(const VkInstanceCreateInfo* pCreateInfo,
                          const VkAllocationCallbacks* pAllocator,
                          VkInstance* pInstance) {
    (void) pCreateInfo;
    (void) pAllocator;
    if (pInstance != NULL) {
        *pInstance = NULL;
    }
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                        "vkCreateInstance -> VK_ERROR_INCOMPATIBLE_DRIVER (no driver)");
    return VK_ERROR_INCOMPATIBLE_DRIVER;
}

/* Nothing to tear down; the only reason to define it is that a caller may look it up. */
void vkDestroyInstance(VkInstance instance, const VkAllocationCallbacks* pAllocator) {
    (void) instance;
    (void) pAllocator;
}

/*
 * A loader with no ICD still reports the API version it implements, so 1.0 with success is
 * the honest answer here — unlike vkCreateInstance, where success would be a lie that the
 * caller would act on.
 */
VkResult vkEnumerateInstanceVersion(uint32_t* pApiVersion) {
    if (pApiVersion != NULL) {
        *pApiVersion = VK_API_VERSION_1_0;
    }
    return VK_SUCCESS;
}

/*
 * No driver means no extensions and no layers. Returning VK_SUCCESS with a count of zero is
 * the documented "nothing available" answer, and it is what a driverless loader does.
 * VK_INCOMPLETE is only for a caller-supplied buffer that was too small, which is not this.
 */
VkResult vkEnumerateInstanceExtensionProperties(const char* pLayerName,
                                               uint32_t* pPropertyCount,
                                               VkExtensionProperties* pProperties) {
    (void) pLayerName;
    (void) pProperties;
    if (pPropertyCount != NULL) {
        *pPropertyCount = 0;
    }
    return VK_SUCCESS;
}

VkResult vkEnumerateInstanceLayerProperties(uint32_t* pPropertyCount,
                                           VkLayerProperties* pProperties) {
    (void) pProperties;
    if (pPropertyCount != NULL) {
        *pPropertyCount = 0;
    }
    return VK_SUCCESS;
}

/*
 * The catch-all returned for any name we do not special-case. A real loader would return
 * NULL for a function it does not know, and callers are supposed to check — but a caller
 * that forgets would jump to address 0 and take the process down. Handing back a function
 * that returns an error is strictly safer, and no more permissive: it cannot succeed.
 */
static VkResult vk_stub_unsupported(void) {
    return VK_ERROR_INITIALIZATION_FAILED;
}

/*
 * This is the entry point libplacebo actually uses, which is why it is the one written out
 * by hand: vkCreateInstance is not among libmpv.so's undefined references at all, so it is
 * reached only through here.
 */
PFN_vkVoidFunction vkGetDeviceProcAddr(VkDevice device, const char* pName);

PFN_vkVoidFunction vkGetInstanceProcAddr(VkInstance instance, const char* pName) {
    static int reported = 0;

    (void) instance;

    if (reported == 0) {
        reported = 1;
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "vulkan stub touched: vkGetInstanceProcAddr(%s)",
                            pName != NULL ? pName : "(null)");
    }

    if (pName == NULL || strncmp(pName, "vk", 2) != 0) {
        return NULL;
    }
    if (strcmp(pName, "vkCreateInstance") == 0) {
        return (PFN_vkVoidFunction) vkCreateInstance;
    }
    if (strcmp(pName, "vkDestroyInstance") == 0) {
        return (PFN_vkVoidFunction) vkDestroyInstance;
    }
    if (strcmp(pName, "vkEnumerateInstanceVersion") == 0) {
        return (PFN_vkVoidFunction) vkEnumerateInstanceVersion;
    }
    if (strcmp(pName, "vkEnumerateInstanceExtensionProperties") == 0) {
        return (PFN_vkVoidFunction) vkEnumerateInstanceExtensionProperties;
    }
    if (strcmp(pName, "vkEnumerateInstanceLayerProperties") == 0) {
        return (PFN_vkVoidFunction) vkEnumerateInstanceLayerProperties;
    }
    if (strcmp(pName, "vkGetInstanceProcAddr") == 0) {
        return (PFN_vkVoidFunction) vkGetInstanceProcAddr;
    }
    if (strcmp(pName, "vkGetDeviceProcAddr") == 0) {
        return (PFN_vkVoidFunction) vkGetDeviceProcAddr;
    }
    return (PFN_vkVoidFunction) vk_stub_unsupported;
}

PFN_vkVoidFunction vkGetDeviceProcAddr(VkDevice device, const char* pName) {
    (void) device;
    if (pName == NULL || strncmp(pName, "vk", 2) != 0) {
        return NULL;
    }
    return (PFN_vkVoidFunction) vk_stub_unsupported;
}

/* ------------------------------------------------------------------ */
/* The remaining entry points — present so RTLD_NOW can resolve them    */
/* ------------------------------------------------------------------ */

/*
 * None of these can be reached: every one of them needs an instance, a physical device or a
 * device, and no instance can ever be created here. Their only job is to exist, because
 * libmpv.so is relocated with RTLD_NOW and a single unresolved vk* name would make the
 * whole dlopen fail — the same mechanism as the missing library, one step later.
 *
 * They take no parameters on purpose. The parameter lists are irrelevant (nothing calls
 * them) and writing 63 of them out by hand would be 63 chances to get one wrong. The
 * *return types*, which would matter if any of them were ever called, are not guessed:
 * they are parsed out of the NDK's vulkan_core.h / vulkan_android.h by
 * apk-check/shimtest/gen_vk_stub.py, which writes this block. Re-run it if the MPV bundle
 * is ever refreshed:
 *
 *     python apk-check/shimtest/gen_vk_stub.py --patch \
 *         webhtv-main/app/src/main/cpp/vulkan_stub.c
 */

#define VK_ERR(name)   VkResult name(void) { return VK_ERROR_INITIALIZATION_FAILED; }
#define VK_VOID(name)  void     name(void) { }

/* ==== BEGIN GENERATED ENTRY POINTS ==== */
/* 由 gen_vk_stub.py 从 vulkan_core.h 生成，不要手改这一段。 */

/* VkResult 入口：一律报初始化失败。没有一个能成功，因为没有 instance 可建。 */
VK_ERR (vkAllocateCommandBuffers)
VK_ERR (vkAllocateDescriptorSets)
VK_ERR (vkAllocateMemory)
VK_ERR (vkBeginCommandBuffer)
VK_ERR (vkBindImageMemory)
VK_ERR (vkCreateAndroidSurfaceKHR)
VK_ERR (vkCreateCommandPool)
VK_ERR (vkCreateComputePipelines)
VK_ERR (vkCreateDescriptorPool)
VK_ERR (vkCreateDescriptorSetLayout)
VK_ERR (vkCreateFence)
VK_ERR (vkCreateFramebuffer)
VK_ERR (vkCreateGraphicsPipelines)
VK_ERR (vkCreateImage)
VK_ERR (vkCreateImageView)
VK_ERR (vkCreatePipelineLayout)
VK_ERR (vkCreateRenderPass)
VK_ERR (vkCreateSampler)
VK_ERR (vkCreateSamplerYcbcrConversion)
VK_ERR (vkCreateSemaphore)
VK_ERR (vkCreateShaderModule)
VK_ERR (vkEndCommandBuffer)
VK_ERR (vkEnumeratePhysicalDevices)
VK_ERR (vkGetFenceStatus)
VK_ERR (vkGetPhysicalDeviceImageFormatProperties2)
VK_ERR (vkQueueSubmit)
VK_ERR (vkResetCommandBuffer)
VK_ERR (vkResetFences)
VK_ERR (vkWaitForFences)

/* void 入口：无事可做。它们同样不可达（没有 instance/device）。 */
VK_VOID(vkCmdBeginRenderPass)
VK_VOID(vkCmdBindDescriptorSets)
VK_VOID(vkCmdBindPipeline)
VK_VOID(vkCmdDispatch)
VK_VOID(vkCmdDraw)
VK_VOID(vkCmdEndRenderPass)
VK_VOID(vkCmdPipelineBarrier)
VK_VOID(vkCmdPushConstants)
VK_VOID(vkDestroyCommandPool)
VK_VOID(vkDestroyDescriptorPool)
VK_VOID(vkDestroyDescriptorSetLayout)
VK_VOID(vkDestroyFence)
VK_VOID(vkDestroyFramebuffer)
VK_VOID(vkDestroyImage)
VK_VOID(vkDestroyImageView)
VK_VOID(vkDestroyPipeline)
VK_VOID(vkDestroyPipelineLayout)
VK_VOID(vkDestroyRenderPass)
VK_VOID(vkDestroySampler)
VK_VOID(vkDestroySamplerYcbcrConversion)
VK_VOID(vkDestroySemaphore)
VK_VOID(vkDestroyShaderModule)
VK_VOID(vkDestroySurfaceKHR)
VK_VOID(vkFreeCommandBuffers)
VK_VOID(vkFreeMemory)
VK_VOID(vkGetDeviceQueue)
VK_VOID(vkGetImageMemoryRequirements)
VK_VOID(vkGetPhysicalDeviceFormatProperties)
VK_VOID(vkGetPhysicalDeviceFormatProperties2)
VK_VOID(vkGetPhysicalDeviceMemoryProperties)
VK_VOID(vkGetPhysicalDeviceProperties2)
VK_VOID(vkGetPhysicalDeviceQueueFamilyProperties2)
VK_VOID(vkUpdateDescriptorSets)
/* ==== END GENERATED ENTRY POINTS ==== */
