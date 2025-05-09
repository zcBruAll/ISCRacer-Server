import com.esotericsoftware.kryonet.Server

object Main {
  def main(args: Array[String]): Unit = {
    // Start a server with
    // TCP on port 7897
    // and UDP on port 7898
    val s = new Server()
    s.start()
    s.bind(7897, 7898)

    println("Server running!")
  }
}