package demo.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshal}
import akka.stream.Materializer
import akka.stream.scaladsl.{BidiFlow, Flow, Source}
import demo.client.HttpClient.FlowType
import demo.core.api.{ReadArtistResponse, _}
import demo.core.serialization.CoreJsonSupport._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.Marshal

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * Created by hollinwilkins on 3/28/16.
  */
object HttpClient {
  type FlowType = Flow[(HttpRequest, Any), (Try[HttpResponse], Any), Any]

  def apply(host: String)
           (implicit ec: ExecutionContext,
            materializer: Materializer,
            system: ActorSystem): HttpClient = {
    val uri = Uri(host)
    val hostName = uri.authority.host.address()
    val port = uri.authority.port
    HttpClient(hostName, port)(ec, materializer, system)
  }
}

case class HttpClient(host: String, port: Int)
                     (override implicit val ec: ExecutionContext,
                      override implicit val materializer: Materializer,
                      implicit val system: ActorSystem) extends Client {
  override def readArtistFlow[Context]: Flow[(ReadArtistRequest, Context), (Future[ReadArtistResponse], Context), Any] = {
    val outbound = Flow[(ReadArtistRequest, Context)].map {
      case (request, context) =>
        val httpRequest = HttpRequest(uri = s"/artists/${request.slug}",
          method = HttpMethods.GET)
        (httpRequest, context)
    }

    BidiFlow.fromFlows(outbound, inbound[ReadArtistResponse, Context]).join(flow[Context])
  }

  override def listSongsFlow[Context]: Flow[(ListSongsRequest, Context), (Future[ListSongsResponse], Context), Any] = {
    val outbound = Flow[(ListSongsRequest, Context)].map {
      case (request, context) =>
        val httpRequest = HttpRequest(uri = s"/artists/${request.artistSlug}/songs",
          method = HttpMethods.GET)
        (httpRequest, context)
    }

    BidiFlow.fromFlows(outbound, inbound[ListSongsResponse, Context]).join(flow[Context])
  }

  override def createArtistFlow[Context]: Flow[(CreateArtistRequest, Context), (Future[CreateArtistResponse], Context), Any] = {
    val outbound = Flow[(CreateArtistRequest, Context)].map {
      case (request, context) => Marshal(request.artist).to[MessageEntity].map(entity => (entity, context))
    }.flatMapConcat(Source.fromFuture).map {
      case (entity, context) =>
        val httpRequest = HttpRequest(uri = "/artists",
          method = HttpMethods.POST,
          entity = entity)
        (httpRequest, context)
    }

    BidiFlow.fromFlows(outbound, inbound[CreateArtistResponse, Context]).join(flow[Context])
  }

  override def createSongFlow[Context]: Flow[(CreateSongRequest, Context), (Future[CreateSongResponse], Context), Any] = {
    val outbound = Flow[(CreateSongRequest, Context)].map {
      case (request, context) => Marshal(request.song).to[MessageEntity].map(entity => (request, entity, context))
    }.flatMapConcat(Source.fromFuture).map {
      case (request, entity, context) =>
        val httpRequest = HttpRequest(uri = s"/artists/${request.song.artistSlug.get}/songs",
          method = HttpMethods.POST,
          entity = entity)
        (httpRequest, context)
    }

    BidiFlow.fromFlows(outbound, inbound[CreateSongResponse, Context]).join(flow[Context])
  }

  private def flow[Context]: Flow[(HttpRequest, Context), (Try[HttpResponse], Context), Any] = {
    Http().cachedHostConnectionPool[Context](host, port)
  }

  private def inbound[Response, Context]
  (implicit um: FromEntityUnmarshaller[Response]): Flow[(Try[HttpResponse], Context), (Future[Response], Context), Any] = {
    Flow[(Try[HttpResponse], Context)].map {
      case (tryResponse, context) =>
        val r = Future.fromTry(tryResponse).flatMap {
          response => Unmarshal(response.entity).to[Response]
        }
        (r, context)
    }
  }
}
