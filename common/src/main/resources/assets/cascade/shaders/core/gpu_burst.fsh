#version 330
#extension GL_ARB_separate_shader_objects : require

// same math as vanilla position_tex_color, so gpu billboards shade exactly like the cpu ones

uniform sampler2D Sampler0;

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUv;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(Sampler0, vUv) * vColor;
}
