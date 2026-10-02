package com.scanner.app

import android.app.Application
import android.graphics.PointF
import com.scanner.app.domain.model.DocumentQuad
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DocumentQuadTest {
    @Test fun rejectsCrossedDegenerateAndNonFiniteCorners() {
        assertTrue(DocumentQuad(PointF(0f, 0f), PointF(100f, 0f), PointF(100f, 100f), PointF(0f, 100f)).isValid())
        assertFalse(DocumentQuad(PointF(0f, 0f), PointF(100f, 100f), PointF(100f, 0f), PointF(0f, 100f)).isValid())
        assertFalse(DocumentQuad(PointF(), PointF(), PointF(), PointF()).isValid())
        assertFalse(DocumentQuad(PointF(Float.NaN, 0f), PointF(100f, 0f), PointF(100f, 100f), PointF(0f, 100f)).isValid())
    }
}
