package de.jzbick.android_demo.renderer

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import de.jzbick.android_demo.util.ShaderUtil
import de.jzbick.android_demo.util.ShaderUtil.checkGLError
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer


class BackgroundRenderer {

    private val TAG = BackgroundRenderer::class.java.simpleName

    // Shader names.
    private val CAMERA_VERTEX_SHADER_NAME = "shaders/screenquad.vert"
    private val CAMERA_FRAGMENT_SHADER_NAME = "shaders/screenquad.frag"
    private val DEPTH_VISUALIZER_VERTEX_SHADER_NAME = "shaders/background_show_depth_color_visualization.vert"
    private val DEPTH_VISUALIZER_FRAGMENT_SHADER_NAME = "shaders/background_show_depth_color_visualization.frag"

    private val COORDS_PER_VERTEX = 2
    private val TEXCOORDS_PER_VERTEX = 2
    private val FLOAT_SIZE = 4

    private lateinit var quadCoords: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer

    private var cameraProgram = 0
    private var depthProgram = 0

    private var cameraPositionAttrib = 0
    private var cameraTexCoordAttrib = 0
    private var cameraTextureUniform = 0
    var cameraTextureId = -1
    private val suppressTimestampZeroRendering = true

    private var depthPositionAttrib = 0
    private var depthTexCoordAttrib = 0
    private var depthTextureUniform = 0
    private var depthTextureId = -1

    fun createOnGlThread(context: Context) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        val textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(textureTarget, cameraTextureId)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val numVertices = 4
        if (numVertices != QUAD_COORDS.size / COORDS_PER_VERTEX) {
            throw RuntimeException("Unexpected number of vertices in BackgroundRenderer.")
        }

        val bbCoords: ByteBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
        bbCoords.order(ByteOrder.nativeOrder())
        quadCoords = bbCoords.asFloatBuffer()
        quadCoords.put(QUAD_COORDS)
        quadCoords.position(0)

        val bbTexCoordsTransformed: ByteBuffer = ByteBuffer.allocateDirect(numVertices * TEXCOORDS_PER_VERTEX * FLOAT_SIZE)
        bbTexCoordsTransformed.order(ByteOrder.nativeOrder())
        quadTexCoords = bbTexCoordsTransformed.asFloatBuffer()

        synchronized(this) {
            val vertexShader: Int = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, CAMERA_VERTEX_SHADER_NAME)
            val fragmentShader: Int = ShaderUtil.loadGLShader(
                TAG, context, GLES20.GL_FRAGMENT_SHADER, CAMERA_FRAGMENT_SHADER_NAME
            )
            cameraProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(cameraProgram, vertexShader)
            GLES20.glAttachShader(cameraProgram, fragmentShader)
            GLES20.glLinkProgram(cameraProgram)
            GLES20.glUseProgram(cameraProgram)
            cameraPositionAttrib = GLES20.glGetAttribLocation(cameraProgram, "a_Position")
            cameraTexCoordAttrib = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord")
            ShaderUtil.checkGLError(TAG, "Program creation")
            cameraTextureUniform = GLES20.glGetUniformLocation(cameraProgram, "sTexture")
            ShaderUtil.checkGLError(TAG, "Program parameters")
        }

        // Load render depth map shader.

        // Load render depth map shader.
        synchronized(this) {
            val vertexShader: Int = ShaderUtil.loadGLShader(
                TAG, context, GLES20.GL_VERTEX_SHADER, DEPTH_VISUALIZER_VERTEX_SHADER_NAME
            )
            val fragmentShader: Int = ShaderUtil.loadGLShader(
                TAG, context, GLES20.GL_FRAGMENT_SHADER, DEPTH_VISUALIZER_FRAGMENT_SHADER_NAME
            )
            depthProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(depthProgram, vertexShader)
            GLES20.glAttachShader(depthProgram, fragmentShader)
            GLES20.glLinkProgram(depthProgram)
            GLES20.glUseProgram(depthProgram)
            depthPositionAttrib = GLES20.glGetAttribLocation(depthProgram, "a_Position")
            depthTexCoordAttrib = GLES20.glGetAttribLocation(depthProgram, "a_TexCoord")
            ShaderUtil.checkGLError(TAG, "Program creation")
            depthTextureUniform = GLES20.glGetUniformLocation(depthProgram, "u_DepthTexture")
            ShaderUtil.checkGLError(TAG, "Program parameters")
        }
    }

    fun draw(frame: Frame, debugShowDepthMap: Boolean) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords
            )
        }
        if (frame.timestamp == 0L && suppressTimestampZeroRendering) {
            return
        }
        draw(debugShowDepthMap)
    }

    fun draw(frame: Frame) {
        draw(frame, false)
    }

    private fun draw(debugShowDepthMap: Boolean) {
        // Ensure position is rewound before use.
        quadTexCoords.position(0)

        // No need to test or write depth, the screen quad has arbitrary depth, and is expected
        // to be drawn first.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (debugShowDepthMap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
            GLES20.glUseProgram(depthProgram)
            GLES20.glUniform1i(depthTextureUniform, 0)

            // Set the vertex positions and texture coordinates.
            GLES20.glVertexAttribPointer(
                depthPositionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadCoords
            )
            GLES20.glVertexAttribPointer(
                depthTexCoordAttrib, TEXCOORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadTexCoords
            )
            GLES20.glEnableVertexAttribArray(depthPositionAttrib)
            GLES20.glEnableVertexAttribArray(depthTexCoordAttrib)
        } else {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            GLES20.glUseProgram(cameraProgram)
            GLES20.glUniform1i(cameraTextureUniform, 0)

            // Set the vertex positions and texture coordinates.
            GLES20.glVertexAttribPointer(
                cameraPositionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadCoords
            )
            GLES20.glVertexAttribPointer(
                cameraTexCoordAttrib, TEXCOORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadTexCoords
            )
            GLES20.glEnableVertexAttribArray(cameraPositionAttrib)
            GLES20.glEnableVertexAttribArray(cameraTexCoordAttrib)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Disable vertex arrays
        if (debugShowDepthMap) {
            GLES20.glDisableVertexAttribArray(depthPositionAttrib)
            GLES20.glDisableVertexAttribArray(depthTexCoordAttrib)
        } else {
            GLES20.glDisableVertexAttribArray(cameraPositionAttrib)
            GLES20.glDisableVertexAttribArray(cameraTexCoordAttrib)
        }

        // Restore the depth state for further drawing.
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        checkGLError(TAG, "BackgroundRendererDraw")
    }

    private val QUAD_COORDS = floatArrayOf(
        -1.0F, -1.0F, +1.0F, -1.0F, -1.0F, +1.0F, +1.0F, +1.0F
    )
}
