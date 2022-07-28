import cats.effect.{IO, SyncIO}
import munit.CatsEffectSuite
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext

import scala.io.AnsiColor._

import quartz.netio._
import java.util.concurrent.Executors
import java.net.InetSocketAddress
import java.nio.channels.{
  AsynchronousChannelGroup,
  AsynchronousServerSocketChannel,
  AsynchronousSocketChannel,
  CompletionHandler
}
import scala.io.AnsiColor

class ExampleSuite extends CatsEffectSuite {
  private def withColor(color: String, s: String): String = s"$color$s$RESET"

  test("Make sure IO computes the right result") {
    //println(withColor(AnsiColor.BLUE, "* Make sure IO computes the right result"))
    IO.pure(1).map(_ + 2) flatMap { result =>
      IO(assertEquals(result, 3)) //>> IO.println("OK")
    }
  }

  test("Plain tcp connection test") {
    //println(withColor(AnsiColor.BLUE, "* TCP Connection test running..."))
    for {

      addr <- IO(new InetSocketAddress("127.0.0.1", 8081))
      server_ch <- TCPChannel.bind( addr, socketGroupThreadsNum = 1 )

      serverFib <- TCPChannel
        .accept(server_ch)
        .flatMap(ch => ch.write(ByteBuffer.wrap(("Hello!\r\n").getBytes())))
        .start

      in <- TCPChannel.connect("127.0.0.1", 8081)

      buf <- in.read(1000)

      text <- IO(new String(buf.toArray))

      _ <- in.close()
      _ <- IO(server_ch.close())

      _ <- IO(assert(text == "Hello!\r\n"))

      _ <- serverFib.join

    } yield ()
  }

  test("TLS connection test with server reply") {
    val ctx: SSLContext = TLSChannel.buildSSLContext("TLS", "keystore.jks", "password")

    def server(server_ch: AsynchronousServerSocketChannel) = {
      for {
        ch <- TCPChannel.accept(server_ch)
        tls_ch <- IO(new TLSChannel(ctx, ch))
        leftOver <- tls_ch.ssl_init()
        output <-
          if (leftOver.isEmpty) /*IO.print(">>> Read: ") >>*/ tls_ch.read(1000)
          else /*IO.print(">>> Leftover: ") >>*/ IO(leftOver)
        text <- IO(new String(output.toArray))
        _ <- IO(assert(text == "Client Hello!\r\n"))

      } yield ()
    }

    for {
      addr <- IO(new InetSocketAddress("127.0.0.1", 8081))
      server_ch <- TCPChannel.bind( addr, socketGroupThreadsNum = 1 )
      serverFib <- server(server_ch).start

      plain <- TCPChannel.connect("127.0.0.1", 8081)
      in <- IO(new TLSChannel(ctx, plain))
      _ <- in.ssl_initClient()
      buf <- in.write(ByteBuffer.wrap("Client Hello!\r\n".getBytes()))

      _ <- serverFib.join

      _ <- IO(server_ch.close())

    } yield ()
  }

}
