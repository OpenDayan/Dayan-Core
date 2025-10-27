package rvtng.bus

object TLUH
{
  def apply(addressBits: Int, dataBits: Int, sourceBits: Int, sinkBits: Int, sizeBits: Int) = {
    val params = TLBundleParameters(
      addressBits = addressBits, dataBits = dataBits, sourceBits = sourceBits, sinkBits = sinkBits, sizeBits = sizeBits,
      hasBCE = false)
    new TLBundle(params)
  }
}