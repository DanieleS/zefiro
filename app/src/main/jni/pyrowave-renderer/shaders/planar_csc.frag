#version 450
#ifdef FRAME_WARP
#extension GL_GOOGLE_include_directive : require
#endif

// Converts PyroWave's three decoded planes (Y, Cb, Cr as normalized code
// values) to display-encoded RGB. 4:2:0 chroma planes are half size and are
// sampled with the same normalized coordinates, which sites them at the
// center of each 2x2 luma quad as the bitstream specifies.
layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2D uLuma;
layout(set = 0, binding = 1) uniform sampler2D uCb;
layout(set = 0, binding = 2) uniform sampler2D uCr;

layout(push_constant) uniform Csc {
    // Rows of the Y'CbCr -> R'G'B' matrix (xyz), applied after the offsets
    vec4 rowR;
    vec4 rowG;
    vec4 rowB;
    // x: luma offset, y: chroma offset, z: luma scale, w: chroma scale
    vec4 offsetScale;
} csc;

#ifdef FRAME_WARP
#include "frame_warp.glsl"
#endif

void main()
{
#ifdef FRAME_WARP
    float warpFade;
    vec2 uv = frameWarpUv(vTexCoord, warpFade);
#else
    vec2 uv = vTexCoord;
#endif
    vec3 ycc = vec3(texture(uLuma, uv).r - csc.offsetScale.x,
                    texture(uCb, uv).r - csc.offsetScale.y,
                    texture(uCr, uv).r - csc.offsetScale.y);
    ycc *= vec3(csc.offsetScale.z, csc.offsetScale.w, csc.offsetScale.w);

    vec3 rgb = vec3(dot(csc.rowR.xyz, ycc), dot(csc.rowG.xyz, ycc), dot(csc.rowB.xyz, ycc));
#ifdef FRAME_WARP
    rgb *= warpFade;
#endif
    outColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
