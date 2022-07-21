# netio

100% async JAVA NIO TCP and TLS interface for Cats Effects 3. All TLS encryption modeled as ZIO effects with javax.net.ssl.SSLEngine.


```scala
trait IOChannel {
  def read( timeOut: Int): IO[Chunk[Byte]]
  def write(buffer: ByteBuffer): IO[Int]
  def close() : IO[Unit]
}
```


IOChannel implemented for plain connection with TCPChannel and TLSChannel for TLS connection respectively.<br> All three methods implement convinient basic protocol which is meant to be used together with fs2.Stream.

Use case example:
Here is example for incoming HTTP2 packet reader with fs2.Stream with netio interface. All it does it spits incomimg arbitatry data blocks into fs2 stream of HTTP2 packets represented as fs2.Chunk[Byte].

```scala
private[this] def makePacketStream(
      ch: IOChannel,
      MAX_FRAME_SIZE: Int,
      leftOver: Chunk[Byte]
  ): Stream[IO, Chunk[Byte]] = {
    val s0 = Stream.chunk[IO, Byte](leftOver)
    val s1 =
      Stream
        .repeatEval(ch.read(HTTP2_KEEP_ALIVE_MS))
        .flatMap(c0 => Stream.chunk(c0))

    def go2(s: Stream[IO, Byte], chunk: Chunk[Byte]): Pull[IO, Byte, Unit] = {

      val bb = chunk.toByteBuffer
      val len = Frames.getLengthField(bb) + 3 + 1 + 1 + 4

      if (chunk.size > len) {
        Pull.output[IO, Byte](chunk.take(len)) >> go2(s, chunk.drop(len))
      } else if (chunk.size == len) Pull.output[IO, Byte](chunk)
      else { go(s, chunk) }
    }

    def go(s: Stream[IO, Byte], leftover: Chunk[Byte]): Pull[IO, Byte, Unit] = {
      s.pull.uncons.flatMap {
        case Some((hd1, tl)) =>
          val hd = leftover ++ hd1
          val bb = hd.toByteBuffer
          val len = Frames.getLengthField(bb) + 3 + 1 + 1 + 4
          (if (hd.size == len) { Pull.output[IO, Byte](hd) >> go(tl, Chunk.empty[Byte]) }
           else if (hd.size > len) {
             Pull.output[IO, Byte](hd.take(len)) >> go2(tl, hd.drop(len)) >> go(tl, Chunk.empty[Byte])
           } else {
             go(tl, hd)
           })

        case None => Pull.done
      }
    }
    go(s0 ++ s1, Chunk.empty[Byte]).stream.chunks
  }
}
```

