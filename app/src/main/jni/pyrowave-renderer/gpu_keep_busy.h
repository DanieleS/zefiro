// Keeps the GPU busy between PyroWave decodes, so its governor keeps its clock up.
//
// Ported from MoreOrLessSoftware/moonlight-android (mlsoft-x, cb655787e). Left to itself, a
// phone's GPU clocks down until it's about 65% busy: on a Pixel 10 Pro decoding 60 PyroWave
// frames a second its clock moved between 512 and 1094 MHz and decodes took 8.1-16.8 ms
// (p10-p90) on the GPU, against 7.9-8.3 ms at 120 frames a second. With this running, decodes
// took 8.6 ms, the jitter buffer shrank from 13.9 to 5.3 ms and capture to present went from
// about 42 to about 30 ms.
//
// Short compute dispatches run back to back, two in flight, on a queue of their own on a device
// of their own, at low global priority where the driver allows, so decodes and draws go ahead
// of them at the next dispatch at the latest. Each is sized to about chunkNs, from how often they
// finish. It costs power and heat: the GPU runs at its top clock for the whole stream, so it's
// only switched on by `adb shell setprop debug.pyrowave.gpu_keep_busy 1`.
//
// The priority doesn't keep a dispatch from running alongside a decode on the GPU's other cores:
// with 256 workgroups the Pixel 10 Pro stayed at its top clock but decodes took 13 ms rather
// than 8. A few workgroups (groups) keep the GPU busy on a core or two and leave the rest to
// the decodes.

#pragma once

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#include <atomic>
#include <cstdint>
#include <thread>

class GpuKeepBusy {
public:
    ~GpuKeepBusy();

    // Starts the work on a device of its own, made from instance and physicalDevice with one
    // queue of queueFamily. groups: workgroups of 64 invocations in each dispatch. Returns false
    // if it couldn't start, leaving nothing behind.
    bool start(PFN_vkGetInstanceProcAddr getInstanceProcAddr, VkInstance instance,
               VkPhysicalDevice physicalDevice, uint32_t queueFamily, int64_t chunkNs, uint32_t groups);
    // Waits for the work in flight, then frees everything. Safe to call more than once.
    void stop();

private:
    static constexpr int kInFlight = 2;
    static constexpr uint32_t kGroupSize = 64;  // keep_busy.comp's local_size_x

    bool createDevice(VkPhysicalDevice physicalDevice, uint32_t queueFamily);
    bool createResources(VkPhysicalDevice physicalDevice, uint32_t queueFamily);
    void destroy();
    void run();
    bool submit(int slot);

#define KEEP_BUSY_DEVICE_FUNCTIONS(X) \
    X(DestroyDevice) X(GetDeviceQueue) X(DeviceWaitIdle) X(QueueSubmit) \
    X(CreateBuffer) X(DestroyBuffer) X(GetBufferMemoryRequirements) X(BindBufferMemory) \
    X(AllocateMemory) X(FreeMemory) \
    X(CreateDescriptorSetLayout) X(DestroyDescriptorSetLayout) X(CreateDescriptorPool) \
    X(DestroyDescriptorPool) X(AllocateDescriptorSets) X(UpdateDescriptorSets) \
    X(CreatePipelineLayout) X(DestroyPipelineLayout) X(CreateShaderModule) X(DestroyShaderModule) \
    X(CreateComputePipelines) X(DestroyPipeline) \
    X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) X(ResetCommandBuffer) \
    X(BeginCommandBuffer) X(EndCommandBuffer) X(CmdBindPipeline) X(CmdBindDescriptorSets) \
    X(CmdPushConstants) X(CmdDispatch) \
    X(CreateFence) X(DestroyFence) X(WaitForFences) X(ResetFences)

#define KEEP_BUSY_DECLARE(name) PFN_vk##name name = nullptr;
    KEEP_BUSY_DEVICE_FUNCTIONS(KEEP_BUSY_DECLARE)
#undef KEEP_BUSY_DECLARE

    PFN_vkGetInstanceProcAddr getInstanceProcAddr_ = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr_ = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties getMemoryProperties_ = nullptr;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    bool lowPriority_ = false;
    VkBuffer buffer_ = VK_NULL_HANDLE;
    VkDeviceMemory memory_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkShaderModule shader_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffers_[kInFlight] = {};
    VkFence fences_[kInFlight] = {};

    int64_t chunkNs_ = 0;
    uint32_t groups_ = 0;
    uint32_t iterations_ = 0;
    std::atomic<bool> quit_ {false};
    std::thread thread_;
};
