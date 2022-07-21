# netio

100% async JAVA NIO TCP and TLS interface for Cats Effects 3. All TLS encryption modeled as ZIO effects with javax.net.ssl.SSLEngine.


`trait IOChannel {
  def read( timeOut: Int): IO[Chunk[Byte]]
  def write(buffer: ByteBuffer): IO[Int]
  def close() : IO[Unit]
}`
