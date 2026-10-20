#version 330
#extension GL_ARB_separate_shader_objects : require

// vanilla 26.1 position_tex_color with the pre-26.1 alpha cutout restored: the old shader discarded
// below 0.1 where the new one only discards exact zero, and cascade's soft sprites rely on that
// threshold to carve their silhouette out of the quad under one-one additive blending

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = color * ColorModulator;
}
