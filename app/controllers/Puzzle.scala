package controllers

import scala.util.{ Try, Success, Failure }

import play.api.i18n.Messages.Implicits._
import play.api.libs.json._
import play.api.mvc._
import play.api.Play.current
import play.twirl.api.Html

import lila.api.Context
import lila.app._
import lila.game.GameRepo
import lila.puzzle.{ PuzzleId, Result, Generated, Puzzle => PuzzleModel, UserInfos }
import lila.user.{ User => UserModel, UserRepo }
import views._

object Puzzle extends LilaController {

  private def env = Env.puzzle

  private def renderJson(
    puzzle: PuzzleModel,
    userInfos: Option[UserInfos],
    mode: String,
    voted: Option[Boolean],
    round: Option[lila.puzzle.Round] = None,
    result: Option[Result] = None)(implicit ctx: Context): Fu[JsObject] =
    lila.puzzle.JsonView(
      puzzle = puzzle,
      userInfos = userInfos,
      mode = mode,
      animationDuration = env.AnimationDuration,
      pref = ctx.pref,
      isMobileApi = ctx.isMobileApi,
      result = result,
      voted = voted)

  private def renderShow(puzzle: PuzzleModel, mode: String)(implicit ctx: Context) =
    env userInfos ctx.me flatMap { infos =>
      renderJson(puzzle = puzzle, userInfos = infos, mode = mode, voted = none) flatMap { json =>
        (!ctx.isMobileApi).??(GameRepo game puzzle.gameId) map { game =>
          views.html.puzzle.show(puzzle, json, game)
        }
      }
    }

  def daily = Open { implicit ctx =>
    OptionFuResult(env.daily() flatMap {
      _.map(_.id) ?? env.api.puzzle.find
    }) { puzzle =>
      negotiate(
        html = renderShow(puzzle, "play") map { Ok(_) },
        api = _ => puzzleJson(puzzle) map { Ok(_) }
      ) map { NoCache(_) }
    }
  }

  def home = Open { implicit ctx =>
    env.selector(ctx.me) flatMap { puzzle =>
      renderShow(puzzle, ctx.isAuth.fold("play", "try")) map { Ok(_) }
    }
  }

  def show(id: PuzzleId) = Open { implicit ctx =>
    OptionFuOk(env.api.puzzle find id) { puzzle =>
      renderShow(puzzle, "play")
    }
  }

  def load(id: PuzzleId) = Open { implicit ctx =>
    XhrOnly {
      OptionFuOk(env.api.puzzle find id)(puzzleJson) map (_ as JSON)
    }
  }

  private def puzzleJson(puzzle: PuzzleModel)(implicit ctx: Context) =
    (env userInfos ctx.me) flatMap { infos =>
      renderJson(puzzle, infos, "play", voted = none)
    }

  // XHR load next play puzzle
  def newPuzzle = Open { implicit ctx =>
    XhrOnly {
      env.selector(ctx.me) zip (env userInfos ctx.me) flatMap {
        case (puzzle, infos) =>
          renderJson(puzzle, infos, ctx.isAuth.fold("play", "try"), voted = none) map { json =>
            Ok(json) as JSON
          }
      } map (_ as JSON)
    }
  }

  def round(id: PuzzleId) = OpenBody { implicit ctx =>
    implicit val req = ctx.body
    OptionFuResult(env.api.puzzle find id) { puzzle =>
      if (puzzle.mate) lila.mon.puzzle.round.mate()
      else lila.mon.puzzle.round.material()
      env.forms.round.bindFromRequest.fold(
        err => fuccess(BadRequest(errorsAsJson(err))),
        resultInt => {
          val result = Result(resultInt == 1)
          ctx.me match {
            case Some(me) =>
              lila.mon.puzzle.round.user()
              env.finisher(puzzle, me, result) flatMap {
                case (newAttempt, None) => UserRepo byId me.id map (_ | me) flatMap { me2 =>
                  env.api.puzzle find id zip
                    (env userInfos me2.some) zip
                    env.api.vote.value(id, me2) flatMap {
                      case ((p2, infos), voted) => renderJson(
                        p2 | puzzle, infos, "view", voted = voted, round = newAttempt.some
                      ) map { Ok(_) }
                    }
                }
                case (oldAttempt, Some(win)) => env.userInfos(me.some) zip
                  ctx.me.?? { env.api.vote.value(puzzle.id, _) } flatMap {
                    case (infos, voted) => renderJson(puzzle, infos, "view",
                      round = oldAttempt.some,
                      result = result.some,
                      voted = voted) map { Ok(_) }
                  }
              }
            case None =>
              lila.mon.puzzle.round.anon()
              env.finisher.incPuzzleAttempts(puzzle)
              renderJson(puzzle, none, "view", result = result.some, voted = none) map { Ok(_) }
          }
        }
      ) map (_ as JSON)
    }
  }

  def round2(id: PuzzleId) = OpenBody { implicit ctx =>
    implicit val req = ctx.body
    OptionFuResult(env.api.puzzle find id) { puzzle =>
      if (puzzle.mate) lila.mon.puzzle.round.mate()
      else lila.mon.puzzle.round.material()
      env.forms.round.bindFromRequest.fold(
        err => fuccess(BadRequest(errorsAsJson(err))),
        resultInt => ctx.me match {
          case Some(me) =>
            lila.mon.puzzle.round.user()
            env.finisher(puzzle, me, Result(resultInt == 1)) >> {
              for {
                me2 <- UserRepo byId me.id map (_ | me)
                infos <- env userInfos me2
              } yield Ok(Json.obj(
                "user" -> lila.puzzle.JsonView.infos(false)(infos)
              ))
            }
          case None =>
            lila.mon.puzzle.round.anon()
            env.finisher.incPuzzleAttempts(puzzle)
            Ok(Json.obj("user" -> false)).fuccess
        }

      ) map (_ as JSON)
    }
  }

  def vote(id: PuzzleId) = AuthBody { implicit ctx => me =>
    implicit val req = ctx.body
    env.forms.vote.bindFromRequest.fold(
      err => fuccess(BadRequest(errorsAsJson(err))),
      vote => env.api.vote.find(id, me) flatMap {
        v => env.api.vote.update(id, me, v, vote == 1)
      } map {
        case (p, a) =>
          if (vote == 1) lila.mon.puzzle.vote.up()
          else lila.mon.puzzle.vote.down()
          Ok(Json.arr(a.value, p.vote.sum))
      }
    ) map (_ as JSON)
  }

  def recentGame = Action.async { req =>
    if (!get("token", req).contains(Env.api.apiToken)) BadRequest.fuccess
    else {
      import akka.pattern.ask
      import makeTimeout.short
      Env.game.recentGoodGameActor ? true mapTo manifest[Option[String]] flatMap {
        _ ?? lila.game.GameRepo.gameWithInitialFen flatMap {
          case Some((game, initialFen)) =>
            Ok(Env.api.pgnDump(game, initialFen.map(_.value)).toString).fuccess
          case _ =>
            lila.log("puzzle import").info("No recent good game, serving a random one :-/")
            lila.game.GameRepo.findRandomFinished(1000) flatMap {
              _ ?? { game =>
                lila.game.GameRepo.initialFen(game) map { fen =>
                  Ok(Env.api.pgnDump(game, fen).toString)
                }
              }
            }
        }
      }
    }
  }

  def importOne = Action.async(parse.json) { implicit req =>
    env.api.puzzle.importOne(req.body, ~get("token", req)) map { id =>
      val url = s"https://lichess.org/training/$id"
      lila.log("puzzle import").info(s"${req.remoteAddress} $url")
      Ok(s"kthxbye $url")
    } recover {
      case e =>
        lila.log("puzzle import").warn(s"${req.remoteAddress} ${e.getMessage}", e)
        BadRequest(e.getMessage)
    }
  }

  def embed = Action { req =>
    Ok {
      val bg = get("bg", req) | "light"
      val theme = get("theme", req) | "brown"
      val url = s"""${req.domain + routes.Puzzle.frame}?bg=$bg&theme=$theme"""
      s"""document.write("<iframe src='//$url&embed=" + document.domain + "' class='lichess-training-iframe' allowtransparency='true' frameBorder='0' style='width: 224px; height: 264px;' title='Lichess free online chess'></iframe>");"""
    } as JAVASCRIPT withHeaders (CACHE_CONTROL -> "max-age=86400")
  }

  def frame = Open { implicit ctx =>
    OptionOk(env.daily()) { daily =>
      html.puzzle.embed(daily)
    }
  }
}
