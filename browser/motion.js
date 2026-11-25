// Predict short motion from the two most recent frames. No future frame is needed.
function createMotionPredictor(width, height) {
    const canvas = new OffscreenCanvas(width,height);
    const gl = canvas.getContext('webgl2',{alpha:false,antialias:false,depth:false,
        preserveDrawingBuffer:true,powerPreference:'high-performance'});
    if (!gl) return null;
    const vertex = `#version 300 es
        out vec2 uv;
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            uv = vec2(p.x, 1.0 - p.y);
        }`;
    function program(fragment) {
        function compile(type, source) {
            const shader = gl.createShader(type);
            gl.shaderSource(shader,source);
            gl.compileShader(shader);
            if (!gl.getShaderParameter(shader,gl.COMPILE_STATUS)) throw Error('Motion shader unavailable');
            return shader;
        }
        const result = gl.createProgram();
        const vs = compile(gl.VERTEX_SHADER,vertex);
        const fs = compile(gl.FRAGMENT_SHADER,'#version 300 es\nprecision highp float;\n' + fragment);
        gl.attachShader(result,vs);
        gl.attachShader(result,fs);
        gl.linkProgram(result);
        gl.deleteShader(vs);
        gl.deleteShader(fs);
        if (!gl.getProgramParameter(result,gl.LINK_STATUS)) throw Error('Motion program unavailable');
        return result;
    }
    const search = program(`
        in vec2 uv;
        out vec4 color;
        uniform sampler2D previous;
        uniform sampler2D current;
        uniform vec2 pixel;
        float difference(vec2 p, vec2 displacement) {
            vec3 a = texture(current,p).rgb;
            vec3 b = texture(previous,p-displacement*pixel).rgb;
            return dot(abs(a-b),vec3(0.299,0.587,0.114));
        }
        float cost(vec2 d) {
            vec2 corner = vec2(6.0,6.0)*pixel;
            return difference(uv,d) + difference(uv+corner,d) + difference(uv-corner,d)
                + difference(uv+vec2(corner.x,-corner.y),d) + difference(uv+vec2(-corner.x,corner.y),d)
                + length(d)*0.001;
        }
        void main() {
            vec2 best = vec2(0.0);
            float score = cost(best);
            for (int y=-2; y<=2; ++y) for (int x=-2; x<=2; ++x) {
                vec2 d = vec2(float(x),float(y))*8.0;
                float error = cost(d);
                if (error < score) { best=d; score=error; }
            }
            vec2 center = best;
            for (int y=-1; y<=1; ++y) for (int x=-1; x<=1; ++x) {
                vec2 d = center + vec2(float(x),float(y))*3.0;
                float error = cost(d);
                if (error < score) { best=d; score=error; }
            }
            if (score > 0.38) best = vec2(0.0);
            color = vec4(best/40.0+0.5,0.0,1.0);
        }`);
    const draw = program(`
        in vec2 uv;
        out vec4 color;
        uniform sampler2D current;
        uniform sampler2D flow;
        uniform vec2 pixel;
        uniform float fraction;
        void main() {
            vec2 d = (texture(flow,vec2(uv.x,1.0-uv.y)).rg-0.5)*40.0*pixel;
            color = texture(current,uv-d*fraction);
        }`);
    function texture() {
        const result = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D,result);
        for (const parameter of [gl.TEXTURE_MIN_FILTER,gl.TEXTURE_MAG_FILTER]) {
            gl.texParameteri(gl.TEXTURE_2D,parameter,gl.LINEAR);
        }
        for (const parameter of [gl.TEXTURE_WRAP_S,gl.TEXTURE_WRAP_T]) {
            gl.texParameteri(gl.TEXTURE_2D,parameter,gl.CLAMP_TO_EDGE);
        }
        return result;
    }
    let previous = texture();
    let current = texture();
    const flow = texture();
    const columns = Math.ceil(width/20);
    const rows = Math.ceil(height/20);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,columns,rows,0,gl.RGBA,gl.UNSIGNED_BYTE,null);
    const target = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER,target);
    gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,flow,0);
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw Error('Motion buffer unavailable');
    const searchPixel = gl.getUniformLocation(search,'pixel');
    const drawFraction = gl.getUniformLocation(draw,'fraction');
    gl.useProgram(search);
    gl.uniform1i(gl.getUniformLocation(search,'previous'),0);
    gl.uniform1i(gl.getUniformLocation(search,'current'),1);
    gl.useProgram(draw);
    gl.uniform1i(gl.getUniformLocation(draw,'current'),1);
    gl.uniform1i(gl.getUniformLocation(draw,'flow'),2);
    gl.uniform2f(gl.getUniformLocation(draw,'pixel'),1/width,1/height);
    function bind(unit, image) {
        gl.activeTexture(gl.TEXTURE0+unit);
        gl.bindTexture(gl.TEXTURE_2D,image);
    }
    let received = 0;
    return {
        push(frame) {
            if (gl.isContextLost()) throw Error('Motion context lost');
            [previous,current] = [current,previous];
            bind(1,current);
            gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,frame);
            if (++received < 2) return false;
            bind(0,previous);
            bind(2,null);
            gl.bindFramebuffer(gl.FRAMEBUFFER,target);
            gl.viewport(0,0,columns,rows);
            gl.useProgram(search);
            gl.uniform2f(searchPixel,1/width,1/height);
            gl.drawArrays(gl.TRIANGLES,0,3);
            return true;
        },
        predict(timestamp, fraction) {
            bind(2,flow);
            gl.bindFramebuffer(gl.FRAMEBUFFER,null);
            gl.viewport(0,0,width,height);
            gl.useProgram(draw);
            gl.uniform1f(drawFraction,fraction);
            gl.drawArrays(gl.TRIANGLES,0,3);
            return new VideoFrame(canvas,{timestamp});
        },
        close() {
            gl.deleteFramebuffer(target);
            for (const image of [previous,current,flow]) gl.deleteTexture(image);
            for (const shader of [search,draw]) gl.deleteProgram(shader);
        }
    };
}
