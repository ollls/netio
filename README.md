# netio

Updates:
Aug 04, 2022 - Test results with netio + fs2 with Http/2 server engine. ( h2load tool - 2.3 GHz 8-Core Intel Core i9 ( MacBook ) )
<br><br>
"http2-quartz", h2spec compatible engine will be pubished separately.

```
h2load -D10 -t4 -c24 -m20 https://localhost:8443/health

finished in 10.00s, 41779.20 req/s, 775.29KB/s
requests: 417792 total, 418272 started, 417792 done, 417792 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 417792 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 7.57MB (7938984) total, 408.00KB (417792) headers (space savings 90.00%), 0B (0) data
                     min         max         mean         sd        +/- sd
time for request:      829us     50.12ms     11.38ms      2.97ms    79.37%
time for connect:    33.41ms     58.89ms     40.77ms      8.70ms    75.00%
time to 1st byte:    41.21ms     65.52ms     49.74ms      8.38ms    70.83%
req/s           :    1717.82     1765.31     1740.70       11.37    66.67%
```

```
h2load -D10 -t4 -c24 -m10 https://localhost:8443/health

finished in 10.00s, 39763.20 req/s, 737.89KB/s
requests: 397632 total, 397872 started, 397632 done, 397632 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 397632 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 7.21MB (7555980) total, 388.31KB (397632) headers (space savings 90.00%), 0B (0) data
                     min         max         mean         sd        +/- sd
time for request:      472us     57.68ms      5.95ms      2.28ms    84.25%
time for connect:    29.60ms     59.79ms     39.51ms     10.97ms    66.67%
time to 1st byte:    37.19ms     64.63ms     51.42ms      7.98ms    62.50%
req/s           :    1617.39     1679.28     1656.77       13.60    70.83%
```

```
h2load -D10 -t4 -c24 -m1 https://localhost:8443/health 
 
finished in 10.00s, 27522.70 req/s, 510.79KB/s
requests: 275227 total, 275251 started, 275227 done, 275227 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 275227 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 4.99MB (5230537) total, 268.78KB (275227) headers (space savings 90.00%), 0B (0) data
                     min         max         mean         sd        +/- sd
time for request:      207us      6.79ms       816us       266us    86.77%
time for connect:    27.56ms     50.05ms     36.13ms      8.34ms    62.50%
time to 1st byte:    32.26ms     54.08ms     41.64ms      7.57ms    58.33%
req/s           :    1131.77     1161.14     1146.76       10.15    50.00%
```

Jul 28, 2022 - Scala 2.13 support in main_2.13 branch ( sbt test fixed as well ). 

### 100% async JAVA NIO TCP and TLS interface for Cats Effects 3.
<br>TCP operations are composed as CATS Effect Async IO with java.nio.channels.CompletionHandler and all TLS decryption/encryption operations are modeled as ZIO effects with javax.net.ssl.SSLEngine.
Refer to /test for examples. More documentation will be provided.


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







