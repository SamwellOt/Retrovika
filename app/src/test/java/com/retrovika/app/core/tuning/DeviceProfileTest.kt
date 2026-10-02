package com.retrovika.app.core.tuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceProfileTest {

    private fun device(gpu: String?, mhz: Int, ramMb: Int = 8_000) = DeviceProfile("Acme", "X1", "soc", ramMb, 8, mhz, gpu)

    @Test
    fun `reconhece a familia e a geracao da GPU`() {
        assertEquals(3, DeviceProfile.gpuScore("Adreno (TM) 740"))
        assertEquals(3, DeviceProfile.gpuScore("Adreno (TM) 830"))
        assertEquals(2, DeviceProfile.gpuScore("Adreno (TM) 660"))
        assertEquals(2, DeviceProfile.gpuScore("Adreno (TM) 730"))
        assertEquals(1, DeviceProfile.gpuScore("Adreno (TM) 619"))
        assertEquals(0, DeviceProfile.gpuScore("Adreno (TM) 506"))
        assertEquals(0, DeviceProfile.gpuScore("Adreno (TM) 702"))
        assertEquals(3, DeviceProfile.gpuScore("Mali-G720-Immortalis MC12"))
        assertEquals(3, DeviceProfile.gpuScore("Immortalis-G715"))
        assertEquals(2, DeviceProfile.gpuScore("Mali-G78 MP14"))
        assertEquals(2, DeviceProfile.gpuScore("Mali-G615 MC6"))
        assertEquals(1, DeviceProfile.gpuScore("Mali-G610 MC4"))
        assertEquals(0, DeviceProfile.gpuScore("Mali-G310"))
        assertEquals(1, DeviceProfile.gpuScore("Mali-G57 MC2"))
        assertEquals(0, DeviceProfile.gpuScore("Mali-G52 MC2"))
        assertEquals(2, DeviceProfile.gpuScore("Samsung Xclipse 920"))
        assertEquals(0, DeviceProfile.gpuScore("PowerVR Rogue GE8320"))
        assertEquals(0, DeviceProfile.gpuScore("ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device))"))
    }

    @Test
    fun `GPU desconhecida ou ausente nao tem nota`() {
        assertNull(DeviceProfile.gpuScore(null))
        assertNull(DeviceProfile.gpuScore("  "))
        assertNull(DeviceProfile.gpuScore("Vivante GC7000"))
    }

    @Test
    fun `a classe e a pior nota entre GPU e CPU`() {
        assertEquals(DeviceTier.TOP, device("Adreno (TM) 750", 3_300).tier)
        assertEquals(DeviceTier.HIGH, device("Adreno (TM) 750", 2_800).tier)
        assertEquals(DeviceTier.MID, device("Adreno (TM) 750", 2_300).tier)
        assertEquals(DeviceTier.ENTRY, device("Mali-G52 MC2", 2_000).tier)
    }

    @Test
    fun `pouca memoria limita a classe`() {
        assertEquals(DeviceTier.ENTRY, device("Adreno (TM) 750", 3_300, ramMb = 2_800).tier)
        assertEquals(DeviceTier.MID, device("Adreno (TM) 750", 3_300, ramMb = 4_000).tier)
        // Um aparelho de 6 GB informa uns 5,4 GB (o sistema reserva uma parte): não pode cair para o meio.
        assertEquals(DeviceTier.TOP, device("Adreno (TM) 750", 3_300, ramMb = 5_400).tier)
    }

    @Test
    fun `sem informacao nenhuma assume o meio`() {
        assertEquals(DeviceTier.MID, device(null, 0).tier)
        // So a GPU sabida basta.
        assertEquals(DeviceTier.TOP, device("Adreno (TM) 750", 0).tier)
    }

    @Test
    fun `a assinatura muda com o aparelho mas nao com a memoria livre`() {
        val a = device("Adreno (TM) 740", 3_000, ramMb = 11_900)
        assertEquals(a.signature, a.copy(gpu = "outra").signature)
        assert(a.signature != a.copy(model = "X2").signature)
    }
}
