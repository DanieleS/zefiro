package com.limelight.binding.video;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Source-level wiring of Frame Warp in the native PyroWave renderer (no device, no GPU, no shader compiler): the
 * shader variant, its second descriptor set, the shift taken from the library for the frame's own number, the redraw
 * once per refresh, and the C entry point. The renderer is C++ called straight from callbacks.c, so none of this
 * touches Java.
 */
public class PyroWaveFrameWarpWiringTest {
    private static final String DIR = "src/main/jni/pyrowave-renderer/";
    private static final String NATIVE = DIR + "pyrowave_renderer.cpp";

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void theWarpShaderIsThePlainOneBuiltWithFrameWarp() throws Exception {
        String frag = read(DIR + "shaders/planar_csc.frag");
        assertTrue(frag.contains("#ifdef FRAME_WARP\n#extension GL_GOOGLE_include_directive : require\n#endif"));
        assertTrue(frag.contains("#ifdef FRAME_WARP\n#include \"frame_warp.glsl\"\n#endif"));
        assertTrue(frag.contains("vec2 uv = frameWarpUv(vTexCoord, warpFade);"));
        assertTrue(frag.contains("rgb *= warpFade;"));
        // Without the define it is the shader it was: the inputs and push constants did not change
        assertTrue(frag.contains("vec2 uv = vTexCoord;"));
        assertTrue(frag.contains("layout(push_constant) uniform Csc {"));
        assertTrue(frag.contains("layout(set = 0, binding = 2) uniform sampler2D uCr;"));
        assertEquals(1, count(frag, "void main()"));
    }

    @Test
    public void theWarpShaderMirrorsFadesAndKeepsTheHudAndTurnsThroughTheLens() throws Exception {
        String warp = read(DIR + "shaders/frame_warp.glsl");
        assertTrue(warp.contains("layout(set = 1, binding = 0) uniform sampler2D u_hud;"));
        assertTrue(warp.contains("layout(set = 1, binding = 1) uniform FrameWarpShift {"));
        // No shift reads no mask; the mask is read only when it applies
        int zero = warp.indexOf("if (frameWarp.shift.x == 0.0 && frameWarp.shift.y == 0.0) return uv;");
        int masked = warp.indexOf("if (frameWarp.shift.z > 0.5) {\n        if (texture(u_hud, uv).r > 0.5) return uv;");
        assertTrue(zero > 0 && masked > zero);
        // From the picture's centre through the lens terms, which is uv less the shift for a flat picture
        int centred = warp.indexOf("vec2 q = uv - 0.5;");
        int turned = warp.indexOf("vec2 p = 0.5 + (q * frameWarp.lens.zw - frameWarp.shift.xy) / "
                + "max(1.0 + dot(q, frameWarp.lens.xy), 0.25);");
        assertTrue(centred > masked && turned > centred);
        assertTrue(warp.indexOf("vec2 outside = max(-p, p - 1.0);") > turned);
        assertTrue(warp.contains("vec2 source = clamp(1.0 - abs(1.0 - abs(p)), 0.0, 1.0);"));
        // A pixel whose shifted position lands on the HUD stays put rather than drawing a copy of the HUD
        assertTrue(warp.contains("if (frameWarp.shift.z > 0.5 && texture(u_hud, source).r > 0.5) return uv;"));
        // Up to 35% darker over the first 8% past the edge
        assertTrue(warp.contains("fade = 1.0 - 0.35 * clamp(max(max(outside.x, outside.y), 0.0) / 0.08, 0.0, 1.0);"));
    }

    // The block the shader reads and the one the renderer fills are one layout: shift, then lens
    @Test
    public void theUniformCarriesTheShiftThenTheLensTerms() throws Exception {
        String warp = read(DIR + "shaders/frame_warp.glsl");
        Matcher block = Pattern.compile("uniform FrameWarpShift \\{(.*?)\\} frameWarp;", Pattern.DOTALL).matcher(warp);
        assertTrue(block.find());
        List<String> members = new ArrayList<>();
        Matcher member = Pattern.compile("(?m)^\\s*(vec4 \\w+);").matcher(block.group(1));
        while (member.find()) members.add(member.group(1));
        assertEquals(Arrays.asList("vec4 shift", "vec4 lens"), members);

        String mask = read("src/main/jni/frame_warp_mask.h");
        assertTrue(mask.contains("constexpr size_t kFrameWarpUniformFloats = 8;"));
        assertTrue(mask.contains("out[i] = i >= 6 ? 1.0f : 0.0f;"));
        String cpp = read(NATIVE);
        assertTrue(cpp.contains("float block[kFrameWarpUniformFloats];"));
        assertTrue(cpp.contains("const VkDescriptorBufferInfo uniform = {m_WarpUniform, 0, "
                + "kFrameWarpUniformFloats * sizeof(float)};"));
        assertTrue(cpp.contains("createMappedBuffer(kFrameWarpUniformFloats * sizeof(float), "
                + "VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,"));
        assertTrue(cpp.contains("std::memcpy(m_WarpUniformMapped, block, sizeof(block));"));
    }

    @Test
    public void buildShEmitsTheWarpVariantAndTheMacroAndSaysItWasNotAssembledByGlslc() throws Exception {
        String build = read(DIR + "shaders/build.sh");
        assertTrue(build.contains("emit planar_csc.frag k_PlanarCscWarpFragSpv -DFRAME_WARP"));
        assertTrue(build.contains("emit planar_csc.frag k_PlanarCscFragSpv\n"));
        assertTrue(build.contains("echo \"#define PYROWAVE_WARP_SPV 1\""));
        assertTrue(build.contains("(also with -DFRAME_WARP)"));
        // The options reach glslc
        assertTrue(build.contains("-mfmt=num \"$@\" -o \"$src.inc\" \"$src\""));
        assertTrue(build.contains("NOT in the committed shaders_spv.h"));
        // The committed header is as the script's output without the warp array, until it is run
        String spv = read(DIR + "shaders_spv.h");
        Matcher macro = Pattern.compile("(?m)^#define PYROWAVE_WARP_SPV (\\d)$").matcher(spv);
        assertTrue(macro.find());
        boolean hasArray = spv.contains("static const uint32_t k_PlanarCscWarpFragSpv[]");
        assertEquals("the macro says whether the array is there", hasArray, macro.group(1).equals("1"));
        assertTrue(spv.contains("static const uint32_t k_PlanarCscFragSpv[]"));
    }

    @Test
    public void theRendererUsesTheWarpShaderOnlyWhereItIsBuilt() throws Exception {
        String cpp = read(NATIVE);
        // Every use of the array is behind the macro; without it Frame Warp declines
        int array = cpp.indexOf("k_PlanarCscWarpFragSpv");
        assertTrue(array > 0);
        // The pointer and its size, in one call
        assertEquals(2, count(cpp, "k_PlanarCscWarpFragSpv"));
        assertTrue(cpp.contains("buildPipeline(k_PlanarCscWarpFragSpv, sizeof(k_PlanarCscWarpFragSpv), "
                + "m_WarpPipelineLayout);"));
        int guard = cpp.lastIndexOf("#if PYROWAVE_WARP_SPV", array);
        int guardEnd = cpp.indexOf("#else", array);
        assertTrue(guard > 0 && guardEnd > array && cpp.indexOf("#endif", guardEnd) > guardEnd);
        assertTrue(cpp.contains("#if !PYROWAVE_WARP_SPV\n        LOGW(\"Frame Warp: this build has no warp shader"));
        // The warp pipeline is the plain pass built from the same function, with set 0 and 1
        assertTrue(cpp.contains("m_Pipeline = buildPipeline(k_PlanarCscFragSpv, sizeof(k_PlanarCscFragSpv), "
                + "m_PipelineLayout);"));
        assertTrue(cpp.contains("const VkDescriptorSetLayout setLayouts[2] = {m_DescriptorSetLayout, m_WarpSetLayout};"));
        assertTrue(cpp.contains("1, 1, &m_WarpSet, 0, nullptr);"));
        assertTrue(cpp.contains("warp ? m_WarpPipeline : m_Pipeline"));
    }

    @Test
    public void theShiftIsTheFrameNumbersAndComesJustBeforeTheSubmit() throws Exception {
        String cpp = read(NATIVE);
        // The frame number rides with the frame from the decode unit to the planes
        assertTrue(cpp.contains("uint32_t frameNumber = 0;"));
        assertTrue(cpp.contains("frame.frameNumber = uint32_t(du->frameNumber);"));
        assertTrue(cpp.contains("m_PictureFrame = frame.frameNumber;"));
        assertTrue(cpp.contains("LiGetFrameWarpShift(m_PictureFrame, LiGetMicroseconds(), &given)"));
        // After the commands are recorded and before the submit
        int end = cpp.indexOf("v.EndCommandBuffer(m_CommandBuffer);\n        // The shift as late as possible");
        int write = cpp.indexOf("writeWarpUniform(drawnPx);");
        int submit = cpp.indexOf("v.QueueSubmit(m_Ctx.queue, 1, &submitInfo, m_FrameFence)");
        assertTrue(end > 0 && write > end && submit > write);
        // The mask copy is recorded before the render pass, which a copy cannot be in
        int mask = cpp.indexOf("recordWarpMask(m_CommandBuffer);");
        int pass = cpp.indexOf("v.CmdBeginRenderPass(m_CommandBuffer");
        assertTrue(mask > 0 && pass > mask);
        assertTrue(cpp.contains("LiGetFrameWarpMask(generation, width, height, bits, capacity)"));
        // The shift is already in the picture's own orientation (y down, rows from the top): no rotation in the renderer
        assertFalse(cpp.contains("pyroSourceUv"));
    }

    @Test
    public void thePictureIsDrawnAgainOncePerRefreshWithoutSpinning() throws Exception {
        String cpp = read(NATIVE);
        assertTrue(cpp.contains("redraw = !m_FrameCv.wait_for(lock, std::chrono::nanoseconds(waitNs), woken);"));
        // Plain wait without Frame Warp: the loop is what it was
        assertTrue(cpp.contains("m_FrameCv.wait(lock, woken);"));
        assertTrue(cpp.contains("return m_WarpOn.load(std::memory_order_acquire) && m_HavePicture && !m_Broken;"));
        // Only once the shift moved, never waiting for the GPU or an image
        assertTrue(cpp.contains("if (!frameWarpMoved(m_WarpDrawnPx[0], m_WarpDrawnPx[1], shift.x, shift.y)) {"));
        assertTrue(cpp.contains("redraw ? 0 : k_FenceTimeoutNs"));
        assertTrue(cpp.contains("redraw ? 0 : 100000000ull"));
        // A tick is always taken before looking, so nothing spins whatever the present mode
        int advance = cpp.indexOf("m_Ticker.advance(now);");
        int moved = cpp.indexOf("if (!frameWarpMoved(m_WarpDrawnPx");
        assertTrue(advance > 0 && moved > advance);
        // A redraw is no new frame
        assertTrue(cpp.contains("if (redraw) {\n            return;\n        }\n        // The redraws follow a refresh after the picture"));
    }

    @Test
    public void theCEntryPointDeclinesWithNothingLeftBehind() throws Exception {
        String header = read(DIR + "pyrowave_renderer.h");
        assertTrue(header.contains("int PyroWaveRendererSetFrameWarp(void);"));
        assertTrue(header.contains("void PyroWaveRendererSetRefreshRate(float hz);"));
        String cpp = read(NATIVE);
        assertTrue(cpp.contains("return g_Renderer && g_Renderer->enableFrameWarp() ? 1 : 0;"));
        int enable = cpp.indexOf("bool enableFrameWarp()");
        int create = cpp.indexOf("if (!createWarpResources()) {", enable);
        int destroy = cpp.indexOf("destroyWarpResources();", create);
        int publish = cpp.indexOf("m_WarpOn.store(true, std::memory_order_release);", create);
        assertTrue(enable > 0 && create > enable && destroy > create && publish > destroy);
        // Torn down with the renderer, after the GPU is idle
        int idle = cpp.indexOf("vk().DeviceWaitIdle(m_Ctx.device);\n        }\n        if (m_Decoder != nullptr) {");
        int teardown = cpp.indexOf("destroySwapchain(VK_NULL_HANDLE);\n        destroyWarpResources();");
        assertTrue(idle > 0 && teardown > idle);
        // Nothing of Java is needed: the entry point is for simplejni.c's wrapper
        assertFalse(cpp.contains("nativeSetFrameWarp"));
        assertFalse(cpp.contains("nativeNoteFrameNumber"));
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }
}
