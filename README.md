# netio - TLS Effects for Quartz-H2 HTTP/2 streaming server. 

April 19, 2024 - Update to make it current with quartz-H2 source code. ( primaraly TLS SNI(Server Name Indication) fixes )<br>
Jul 28, 2022 - Scala 2.13 support in main_2.13 branch ( sbt test fixed as well ). 

### 100% async JAVA NIO TCP and TLS interface for Cats Effects 3.
<br>TCP operations are composed as CATS Effect Async IO with java.nio.channels.CompletionHandler and all TLS decryption/encryption operations are modeled as ZIO effects with javax.net.ssl.SSLEngine.
Refer to /test for examples. More documentation will be provided.

Also you may want to check-out https://github.com/ollls/quartz-h2 ( HTTP2 streamer based on netio on Scala3 )


```scala
trait IOChannel {
  def read( timeOut: Int): IO[Chunk[Byte]]
  def write(buffer: ByteBuffer): IO[Int]
  def close() : IO[Unit]
}
```


IOChannel implemented for plain connection with TCPChannel and TLSChannel for TLS connection respectively.<br> All three methods implement convenient basic protocol which is meant to be used together with fs2.Stream. At the code level TCP or TLS happens transparently, after relvant channel initialization at the beginning.

Use cases:<br>

* Here is example for incoming HTTP2 packet reader with fs2.Stream with netio interface. All it does it spits incomimg arbitatry data blocks into fs2 stream of HTTP2 packets represented as fs2.Chunk[Byte].

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

* Simplified HTTP1 Chunked fs2.Stream converter.

```scala
  private[this] def makeChunkedStream(leftOver: Chunk[Byte]) = {
    val s0 = Stream.chunk[IO, Byte](leftOver)
    val s1 = Stream.repeatEval(ch.read(TIMEOUT_MS)).flatMap(c0 => Stream.chunk(c0))

    def go2(s: Stream[IO, Byte], hd: Chunk[Byte]): Pull[IO, Byte, Unit] = {
      val (chunkSize, offset) = extractChunkLen2(hd)
      val len = hd.size - offset // actual data avaialble
 
      if (chunkSize == 0) Pull.done
      else if (len == chunkSize + 2)
        Pull.output[IO, Byte](hd.drop(offset).take(chunkSize)) >> go(
          s,
          chunk_drop_with_validation(hd, offset + chunkSize)
        )
      else if (len >= chunkSize + 2) // account for chunk end marking
        Pull.output[IO, Byte](hd.drop(offset).take(chunkSize)) >> go2(
          s,
          chunk_drop_with_validation(hd, offset + chunkSize)
        )
      else go(s, hd)
    }
    
    

  def extractChunkLen2(db: Chunk[Byte]): (Int, Int) = {
    var l = List.empty[Char]
    var c: Byte = 0
    var i: Int = 0
    while {
      { c = db(i); i += 1 }
      c != '\r' && i < 8 // 8 chars for chunked len
    } do (l = l.appended(c.toChar))
    if (c == '\r' && db(i) == '\n') i += 1
    else throw (new ChunkedEncodingError(""))
    (Integer.parseInt(l.mkString, 16), i)
  }


  def chunk_drop_with_validation(data: Chunk[Byte], offset: Int) = {
    val data2 = data.drop(offset)
    // validate end of chunk
    val test = (data2(0) == '\r' && data2(1) == '\n')
    if (test == false) throw (new ChunkedEncodingError(""))
    data2.drop(2) // get rid of end of block markings
  }
  
  ```







