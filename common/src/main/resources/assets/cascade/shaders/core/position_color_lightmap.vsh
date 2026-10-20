#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

// vertex stage for lit solid mesh particles: vanilla dropped its position_color_lightmap shader in 26.1,
// so this vendors the same idea, folding the lightmap tint into the vertex color like the particle shader

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in ivec2 UV2;

uniform sampler2D Sampler2;

layout(location = 0) out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color * sample_lightmap(Sampler2, UV2);
}
