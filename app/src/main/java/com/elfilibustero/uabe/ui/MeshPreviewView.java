package com.elfilibustero.uabe.ui;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class MeshPreviewView extends GLSurfaceView {
    private final RendererImpl meshRenderer;
    private float lastX, lastY, lastDistance;
    private boolean dragging;

    public MeshPreviewView(Context context) {
        super(context);
        meshRenderer = initializeRenderer();
    }

    public MeshPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        meshRenderer = initializeRenderer();
    }

    public MeshPreviewView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs);
        meshRenderer = initializeRenderer();
    }

    private RendererImpl initializeRenderer() {
        setEGLContextClientVersion(1);
        RendererImpl renderer = new RendererImpl();
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setFocusable(true);
        return renderer;
    }

    public void setObjData(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data OBJ kosong");
        }

        Geometry geometry = parseObj(new String(data, StandardCharsets.UTF_8));

        queueEvent(() -> meshRenderer.setGeometry(geometry));
        requestRender();
    }

    public void resetView() {
        queueEvent(meshRenderer::reset);
        requestRender();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            lastX = event.getX();
            lastY = event.getY();
            dragging = true;
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
            lastDistance = distance(event);
            dragging = false;
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (event.getPointerCount() >= 2) {
                float distance = distance(event);
                if (lastDistance > 0 && distance > 0) {
                    float factor = distance / lastDistance;
                    queueEvent(() -> meshRenderer.zoomBy(factor));
                    requestRender();
                }
                lastDistance = distance;
            } else if (dragging) {
                float x = event.getX();
                float y = event.getY();
                float dx = x - lastX;
                float dy = y - lastY;
                queueEvent(() -> meshRenderer.rotateBy(dx * 0.7f, dy * 0.7f));
                requestRender();
                lastX = x;
                lastY = y;
            }
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_UP) {
            lastDistance = 0;
            dragging = false;
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            lastDistance = 0;
            return true;
        }

        return true;
    }

    private static float distance(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0;
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static final class Geometry {
        final float[] vertices;
        final short[] indices;

        Geometry(float[] vertices, short[] indices) {
            this.vertices = vertices;
            this.indices = indices;
        }
    }

    private static Geometry parseObj(String text) {
        List<float[]> positions = new ArrayList<>();
        List<Short> triangles = new ArrayList<>();

        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            String[] parts = trimmed.split("\\s+");

            try {
                if ("v".equals(parts[0]) && parts.length >= 4) {
                    float x = Float.parseFloat(parts[1]);
                    float y = Float.parseFloat(parts[2]);
                    float z = Float.parseFloat(parts[3]);

                    if (Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)) {
                        positions.add(new float[]{x, y, z});
                    }
                } else if ("f".equals(parts[0]) && parts.length >= 4) {
                    int[] face = new int[parts.length - 1];

                    for (int i = 1; i < parts.length; i++) {
                        String token = parts[i];
                        int slash = token.indexOf('/');
                        String indexText = slash >= 0 ? token.substring(0, slash) : token;
                        int index = Integer.parseInt(indexText);

                        int resolved = index > 0 ? index - 1 : positions.size() + index;
                        if (resolved < 0 || resolved >= positions.size()) {
                            throw new IllegalArgumentException("Indeks face OBJ di luar batas");
                        }
                        face[i - 1] = resolved;
                    }

                    for (int i = 1; i < face.length - 1; i++) {
                        int a = face[0], b = face[i], c = face[i + 1];
                        if (a > 32767 || b > 32767 || c > 32767) {
                            throw new IllegalArgumentException("Mesh terlalu besar untuk indeks ES1");
                        }
                        triangles.add((short) a);
                        triangles.add((short) b);
                        triangles.add((short) c);
                    }
                }
            } catch (NumberFormatException ignored) {
                // Abaikan baris OBJ yang tidak valid.
            }
        }

        if (positions.isEmpty() || triangles.isEmpty()) {
            throw new IllegalArgumentException("OBJ tidak memiliki vertex dan face yang valid");
        }
        if (positions.size() > 32768) {
            throw new IllegalArgumentException("Mesh terlalu besar untuk preview ES1");
        }

        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;

        for (float[] p : positions) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            minZ = Math.min(minZ, p[2]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
            maxZ = Math.max(maxZ, p[2]);
        }

        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float extent = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        float scale = extent > 0 && Float.isFinite(extent) ? 2.0f / extent : 1.0f;

        float[] vertices = new float[positions.size() * 3];
        for (int i = 0; i < positions.size(); i++) {
            float[] p = positions.get(i);
            vertices[i * 3] = (p[0] - cx) * scale;
            vertices[i * 3 + 1] = (p[1] - cy) * scale;
            vertices[i * 3 + 2] = (p[2] - cz) * scale;
        }

        short[] indices = new short[triangles.size()];
        for (int i = 0; i < triangles.size(); i++) indices[i] = triangles.get(i);

        return new Geometry(vertices, indices);
    }

    private static final class RendererImpl implements GLSurfaceView.Renderer {
        private FloatBuffer vertexBuffer;
        private ShortBuffer indexBuffer;
        private int indexCount;
        private float rotationX = 15f;
        private float rotationY = -25f;
        private float zoom = 1f;
        private int width = 1, height = 1;

        void setGeometry(Geometry geometry) {
            ByteBuffer vb = ByteBuffer.allocateDirect(geometry.vertices.length * 4)
                    .order(ByteOrder.nativeOrder());
            vertexBuffer = vb.asFloatBuffer();
            vertexBuffer.put(geometry.vertices).position(0);

            ByteBuffer ib = ByteBuffer.allocateDirect(geometry.indices.length * 2)
                    .order(ByteOrder.nativeOrder());
            indexBuffer = ib.asShortBuffer();
            indexBuffer.put(geometry.indices).position(0);
            indexCount = geometry.indices.length;
        }

        void rotateBy(float dx, float dy) {
            rotationY += dx;
            rotationX += dy;
        }

        void zoomBy(float factor) {
            zoom = Math.max(0.25f, Math.min(5f, zoom * factor));
        }

        void reset() {
            rotationX = 15f;
            rotationY = -25f;
            zoom = 1f;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            gl.glClearColor(0.08f, 0.09f, 0.11f, 1f);
            gl.glEnable(GL10.GL_DEPTH_TEST);
            gl.glDisable(GL10.GL_CULL_FACE);
            gl.glShadeModel(GL10.GL_SMOOTH);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            width = Math.max(1, w);
            height = Math.max(1, h);
            gl.glViewport(0, 0, width, height);
            gl.glMatrixMode(GL10.GL_PROJECTION);
            gl.glLoadIdentity();

            float aspect = (float) width / height;
            float halfHeight = 1.6f;
            float halfWidth = halfHeight * aspect;
            gl.glFrustumf(-halfWidth, halfWidth, -halfHeight, halfHeight, 1.5f, 30f);
            gl.glMatrixMode(GL10.GL_MODELVIEW);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
            gl.glMatrixMode(GL10.GL_MODELVIEW);
            gl.glLoadIdentity();
            gl.glTranslatef(0f, 0f, -5f);
            gl.glScalef(zoom, zoom, zoom);
            gl.glRotatef(rotationX, 1f, 0f, 0f);
            gl.glRotatef(rotationY, 0f, 1f, 0f);

            if (vertexBuffer == null || indexBuffer == null || indexCount == 0) return;

            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
            gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vertexBuffer);
            gl.glColor4f(0.15f, 0.82f, 0.92f, 1f);
            gl.glDrawElements(GL10.GL_TRIANGLES, indexCount,
                    GL10.GL_UNSIGNED_SHORT, indexBuffer);
            gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
        }
    }
}
