package org.sunbird.job.collectioncert.functions

import com.datastax.driver.core.querybuilder.QueryBuilder
import com.datastax.driver.core.{Row, TypeTokens}
import com.google.common.reflect.TypeToken
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.cache.DataCache
import org.sunbird.job.collectioncert.domain._
import org.sunbird.job.collectioncert.task.CollectionCertPreProcessorConfig
import org.sunbird.job.util.{CassandraUtil, HttpUtil, JSONUtil, ScalaJsonUtil}

import java.text.SimpleDateFormat
import java.util
import java.util.Date
import scala.collection.JavaConverters._

trait IssueCertificateHelper {
    private[this] val logger = LoggerFactory.getLogger(classOf[CollectionCertPreProcessorFn])


    def issueCertificate(event:Event, template: Map[String, String])(cassandraUtil: CassandraUtil, cache:DataCache, contentCache: DataCache, metrics: Metrics, config: CollectionCertPreProcessorConfig, httpUtil: HttpUtil): String = {
        //validCriteria
        logger.info("issueCertificate i/p event =>"+event)
        val criteria = validateTemplate(template, event.batchId)(config)
        //validateEnrolmentCriteria
        val certName = template.getOrElse(config.name, "")
        val additionalProps: Map[String, List[String]] = ScalaJsonUtil.deserialize[Map[String, List[String]]](template.getOrElse("additionalProps", "{}"))

        val enrolledUser: EnrolledUser = validateEnrolmentCriteria(event, criteria.getOrElse(config.enrollment, Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]], certName, additionalProps)(metrics, cassandraUtil, config)
        logger.info("IssueCertificateHelper:: issueCertificate:: enrolledUser:: "+enrolledUser)

        val attemptDetails: Map[String, AnyRef] = if(event.attemptId.nonEmpty) getAttemptDetails(event)(metrics, cassandraUtil, config) else Map.empty[String, AnyRef]
        logger.info("IssueCertificateHelper:: issueCertificate:: attemptDetails:: "+attemptDetails)

        //validateAssessmentCriteria
        val assessedUser = validateAssessmentCriteria(event, criteria.getOrElse(config.assessment, Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]], enrolledUser.userId, additionalProps, attemptDetails)(metrics, cassandraUtil, contentCache, config)
        logger.info("IssueCertificateHelper:: issueCertificate:: assessedUser:: "+assessedUser)

        //validateUserCriteria
        val userDetails = validateUser(assessedUser.userId, criteria.getOrElse(config.user, Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]])(metrics, config, httpUtil)
        logger.info("IssueCertificateHelper:: issueCertificate:: userDetails:: "+userDetails)

        //generateCertificateEvent
        if(userDetails.nonEmpty) {
            generateCertificateEvent(event, template, userDetails, enrolledUser, assessedUser, additionalProps, certName, attemptDetails)(metrics, config, cache, httpUtil)
        } else {
            logger.info(s"""User :: ${event.userId} did not match the criteria for batch :: ${event.batchId} and course :: ${event.courseId}""")
            null
        }
    }

    def validateTemplate(template: Map[String, String], batchId: String)(config: CollectionCertPreProcessorConfig):Map[String, AnyRef] = {
        val criteria = ScalaJsonUtil.deserialize[Map[String, AnyRef]](template.getOrElse(config.criteria, "{}"))
        if(template.getOrElse("url", "").nonEmpty && criteria.nonEmpty && criteria.keySet.intersect(Set(config.enrollment, config.assessment, config.users)).nonEmpty) {
            criteria
        } else {
            throw new Exception(s"Invalid template for batch : $batchId")
        }
    }

    def validateEnrolmentCriteria(event: Event, enrollmentCriteria: Map[String, AnyRef], certName: String, additionalProps: Map[String, List[String]])(metrics:Metrics, cassandraUtil: CassandraUtil, config:CollectionCertPreProcessorConfig): EnrolledUser = {
        if(enrollmentCriteria.nonEmpty) {
            val query = QueryBuilder.select().from(config.keyspace, config.userEnrolmentsTable)
              .where(QueryBuilder.eq(config.dbUserId, event.userId)).and(QueryBuilder.eq(config.dbCourseId, event.courseId))
              .and(QueryBuilder.eq(config.dbBatchId, event.batchId))
          logger.info("IssueCertificateHelper:: validateEnrolmentCriteria:: query:: " + query.toString)
            val row = cassandraUtil.findOne(query.toString)
            metrics.incCounter(config.dbReadCount)
            val enrolmentAdditionProps = additionalProps.getOrElse(config.enrollment, List[String]())
            if(null != row){
                val active:Boolean = row.getBool(config.active)
                val issuedCertificates = row.getList(config.issuedCertificates, TypeTokens.mapOf(classOf[String], classOf[String])).asScala.toList
                val isCertIssued = issuedCertificates.nonEmpty && issuedCertificates.exists(cert => certName.equalsIgnoreCase(cert.getOrDefault(config.name, "")))
                val status = row.getInt(config.status)
                val criteriaStatus = enrollmentCriteria.getOrElse(config.status, 2)
                val oldId = if(isCertIssued && event.reIssue) issuedCertificates.filter(cert => certName.equalsIgnoreCase(cert.getOrDefault(config.name,"")))
                  .map(cert => cert.getOrDefault(config.identifier, "")).head else ""
                val userId = if(active && (criteriaStatus == status) && (!isCertIssued || event.reIssue)) event.userId else ""
                val issuedOn = row.getTimestamp(config.completedOn)
                val addProps = enrolmentAdditionProps.map(prop => prop -> row.getObject(prop.toLowerCase)).toMap
                EnrolledUser(userId, oldId, issuedOn, {if(addProps.nonEmpty) Map[String, Any](config.enrollment -> addProps) else Map()})
            } else EnrolledUser("", "")
        } else EnrolledUser(event.userId, "")
    }

    def validateAssessmentCriteria(event: Event, assessmentCriteria: Map[String, AnyRef], enrolledUser: String, additionalProps: Map[String, List[String]], attemptDetails: Map[String, AnyRef])(metrics:Metrics, cassandraUtil: CassandraUtil, contentCache: DataCache, config:CollectionCertPreProcessorConfig):AssessedUser = {
      logger.info("IssueCertificateHelper:: validateAssessmentCriteria:: assessmentCriteria:: " + assessmentCriteria + " || enrolledUser:: " + enrolledUser + " || attemptDetails:: " + attemptDetails)
      if(assessmentCriteria.nonEmpty && enrolledUser.nonEmpty) {
            val scoreTuple = if(attemptDetails.nonEmpty) {
                val score:Double =  attemptDetails.getOrElse("score", 0d).asInstanceOf[Double]
                val max_score:Double =  attemptDetails.getOrElse("max_score", 0d).asInstanceOf[Double]
                (score,Map(attemptDetails.getOrElse("content_id","").asInstanceOf[String] -> (score * 100 / max_score)))
            } else {
                val filteredUserAssessments = getMaxScore(event)(metrics, cassandraUtil, config, contentCache)
                val scoreMap = filteredUserAssessments.map(sc => sc._1 -> (sc._2.head.score * 100 / sc._2.head.totalScore))
                val score:Double = if (scoreMap.nonEmpty) scoreMap.values.max else 0d
                (score, scoreMap)
            }
            logger.info("IssueCertificateHelper:: validateAssessmentCriteria:: scoreTuple:: " + scoreTuple)
            val addProps = getAddProps(additionalProps, scoreTuple._2)(config)
            logger.info("IssueCertificateHelper:: validateAssessmentCriteria:: addProps:: " + addProps)
            if(isValidAssessCriteria(assessmentCriteria, scoreTuple._1)) {
              logger.info("IssueCertificateHelper:: validateAssessmentCriteria:: assessedUser " )
                AssessedUser(enrolledUser, {if(addProps.nonEmpty) Map[String, Any](config.assessment -> addProps) else Map()})
            } else AssessedUser("")
        } else AssessedUser(enrolledUser)
    }

    def getAddProps(additionalProps: Map[String, List[String]], scoreMap: Map[String, Double])(config:CollectionCertPreProcessorConfig): Map[String, AnyRef] = {
        val assessmentAdditionProps = additionalProps.getOrElse(config.assessment, List())
        if (assessmentAdditionProps.nonEmpty && assessmentAdditionProps.contains("score")) Map("score" -> scoreMap) else Map()
    }

    def validateUser(userId: String, userCriteria: Map[String, AnyRef])(metrics:Metrics, config:CollectionCertPreProcessorConfig, httpUtil: HttpUtil): Map[String, AnyRef] = {
        if(userId.nonEmpty) {
            val url = config.learnerBasePath + config.userReadApi + "/" + userId + "?organisations,roles,locations,declarations,externalIds"
            val result = getAPICall(url, "response")(config, httpUtil, metrics)
            if(userCriteria.isEmpty || userCriteria.size == userCriteria.count(uc => uc._2 == result.getOrElse(uc._1, null))) {
                result
            } else Map[String, AnyRef]()
        } else Map[String, AnyRef]()
    }

    def getMaxScore(event: Event)(metrics:Metrics, cassandraUtil: CassandraUtil, config:CollectionCertPreProcessorConfig, contentCache: DataCache):Map[String, Set[AssessmentUserAttempt]] = {
        val contextId = "cb:" + event.batchId
        val query = QueryBuilder.select().column("aggregates").column("agg").from(config.keyspace, config.userActivityAggTable)
          .where(QueryBuilder.eq("activity_type", "Course")).and(QueryBuilder.eq("activity_id", event.courseId))
          .and(QueryBuilder.eq("user_id", event.userId)).and(QueryBuilder.eq("context_id", contextId))

        val rows: java.util.List[Row] = cassandraUtil.find(query.toString)
        metrics.incCounter(config.dbReadCount)
        if(null != rows && !rows.isEmpty) {
            val aggregates: Map[String, Double] = rows.asScala.toList.head
              .getMap("aggregates", classOf[String], classOf[java.lang.Double]).asScala.map(e => e._1 -> e._2.toDouble)
              .toMap
            val agg: Map[String, Double] = rows.asScala.toList.head.getMap("agg", classOf[String], classOf[Integer])
              .asScala.map(e => e._1 -> e._2.toDouble).toMap
            val aggs: Map[String, Double] = agg ++ aggregates
            val userAssessments = aggs.keySet.filter(key => key.startsWith("score:")).map(
                key => {
                    val id= key.replaceAll("score:", "")
                    AssessmentUserAttempt(id, aggs.getOrElse("score:" + id, 0d), aggs.getOrElse("max_score:" + id, 1d))
                }).groupBy(f => f.contentId)

            val filteredUserAssessments = userAssessments.filterKeys(key => {
                val metadata = contentCache.getWithRetry(key)
                if (metadata.nonEmpty) {
                    val contentType = metadata.getOrElse("contenttype", "")
                    config.assessmentContentTypes.contains(contentType)
                } else if(metadata.isEmpty && config.enableSuppressException){
                    logger.error("Suppressed exception: Metadata cache not available for: " + key)
                    false
                } else throw new Exception("Metadata cache not available for: " + key)
            })
            // TODO: Here we have an assumption that, we will consider max percentage from all the available attempts of different assessment contents.
            if (filteredUserAssessments.nonEmpty) filteredUserAssessments else Map()
        } else Map()
    }

    def isValidAssessCriteria(assessmentCriteria: Map[String, AnyRef], score: Double): Boolean = {
        if(assessmentCriteria.get("score").isInstanceOf[Number]) {
            score == assessmentCriteria.get("score").asInstanceOf[Int].toDouble
        } else {
            val scoreCriteria = assessmentCriteria.getOrElse("score", Map[String, AnyRef]()).asInstanceOf[Map[String, Int]]
            if(scoreCriteria.isEmpty) false
            else {
                val operation = scoreCriteria.head._1
                val criteriaScore = scoreCriteria.head._2.toDouble
                operation match {
                    case "EQ" => score == criteriaScore
                    case "eq" => score == criteriaScore
                    case "=" => score == criteriaScore
                    case ">" => score > criteriaScore
                    case "<" => score < criteriaScore
                    case ">=" => score >= criteriaScore
                    case "<=" => score <= criteriaScore
                    case "ne" => score != criteriaScore
                    case "!=" => score != criteriaScore
                    case _ => false
                }
            }

        }
    }

    def getAPICall(url: String, responseParam: String)(config:CollectionCertPreProcessorConfig, httpUtil: HttpUtil, metrics: Metrics): Map[String,AnyRef] = {
        val response = httpUtil.get(url, config.defaultHeaders)
        if(200 == response.status) {
            ScalaJsonUtil.deserialize[Map[String, AnyRef]](response.body)
              .getOrElse("result", Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]]
              .getOrElse(responseParam, Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]]
        } else if(400 == response.status && response.body.contains(config.userAccBlockedErrCode)) {
            metrics.incCounter(config.skippedEventCount)
            logger.error(s"Error while fetching user details for ${url}: " + response.status + " :: " + response.body)
            Map[String, AnyRef]()
        } else {
            throw new Exception(s"Error from get API : $url, with response: $response")
        }
    }

    def getCourseName(courseId: String)(metrics:Metrics, config:CollectionCertPreProcessorConfig, cache:DataCache, httpUtil: HttpUtil): String = {
        val courseMetadata = cache.getWithRetry(courseId)
        if(null == courseMetadata || courseMetadata.isEmpty) {
            val url = config.contentBasePath + config.contentReadApi + "/" + courseId + "?fields=name"
            val response = getAPICall(url, "content")(config, httpUtil, metrics)
            StringContext.processEscapes(response.getOrElse(config.name, "").asInstanceOf[String]).filter(_ >= ' ')
        } else {
            StringContext.processEscapes(courseMetadata.getOrElse(config.name, "").asInstanceOf[String]).filter(_ >= ' ')
        }
    }

    def getAttemptDetails(event: Event)(metrics:Metrics, cassandraUtil: CassandraUtil, config:CollectionCertPreProcessorConfig):Map[String, AnyRef] = {
        val contextId = "cb:" + event.batchId
        val query = QueryBuilder.select().column("agg_details").from(config.keyspace, config.userActivityAggTable)
          .where(QueryBuilder.eq("activity_type", "Course")).and(QueryBuilder.eq("activity_id", event.courseId))
          .and(QueryBuilder.eq("user_id", event.userId)).and(QueryBuilder.eq("context_id", contextId))
        logger.info("IssueCertificateHelper:: getAttemptDetails:: query:: " + query)
        val DATE_FORMAT = "MMM dd, yyyy, h:mm:ss a"
        val dateFormat = new SimpleDateFormat(DATE_FORMAT)

        val row: Row = cassandraUtil.findOne(query.toString)
        logger.info("IssueCertificateHelper:: getAttemptDetails:: row:: " + row)
        metrics.incCounter(config.dbReadCount)
        if(null != row) {
            val aggDetailsMapList: List[Map[String,AnyRef]] = row.getList("agg_details", new TypeToken[String](){}).asScala.toList.map(rec=> {
                logger.info("IssueCertificateHelper:: getAttemptDetails:: rec:: " + rec)
                val deserMap = JSONUtil.deserialize[util.Map[String, AnyRef]](rec)
                deserMap.put("last_attempted_on", dateFormat.parse(deserMap.get("last_attempted_on").asInstanceOf[String]))
                logger.info("IssueCertificateHelper:: getAttemptDetails:: deserMap:: " + deserMap)
                deserMap.asScala.toMap
            })
            logger.info("IssueCertificateHelper:: getAttemptDetails:: aggDetailsMapList:: " + aggDetailsMapList)
            val sortedListMap: List[Map[String,AnyRef]] = aggDetailsMapList.sortBy(_("last_attempted_on").asInstanceOf[Date])(Ordering[Date])
            logger.info("IssueCertificateHelper:: getAttemptDetails:: sortedListMap:: " + sortedListMap)
            val aggDetails: Map[String, AnyRef] = sortedListMap.filter(rec=> rec.getOrElse("attempt_id","").asInstanceOf[String].equalsIgnoreCase(event.attemptId)).head
            val attempt_count: Double = sortedListMap.indexWhere(rec => {rec.getOrElse("attempt_id","").asInstanceOf[String].equalsIgnoreCase(event.attemptId)}) + 1
            logger.info("IssueCertificateHelper:: getAttemptDetails:: attempt_count:: " + attempt_count + " || attempt_id:: " + aggDetails.getOrElse("attempt_id","") + " || score:: " + aggDetails.getOrElse("score",0) + " || max_score:: " + aggDetails.getOrElse("max_score",0) + " || content_id:: " + aggDetails.getOrElse("content_id",""))
            Map("attempt_count" -> attempt_count.asInstanceOf[AnyRef], "attempt_id" -> aggDetails.getOrElse("attempt_id",""), "score" -> aggDetails.getOrElse("score", 0.asInstanceOf[AnyRef]), "max_score" -> aggDetails.getOrElse("max_score", 0.asInstanceOf[AnyRef]), "content_id" -> aggDetails.getOrElse("content_id", ""))
        } else Map.empty[String, AnyRef]
    }

    def generateCertificateEvent(event: Event, template: Map[String, String], userDetails: Map[String, AnyRef], enrolledUser: EnrolledUser, assessedUser: AssessedUser, additionalProps: Map[String, List[String]], certName: String, attemptDetails: Map[String, AnyRef])(metrics:Metrics, config:CollectionCertPreProcessorConfig, cache:DataCache, httpUtil: HttpUtil): String = {
        val firstName = Option(userDetails.getOrElse("firstName", "").asInstanceOf[String]).getOrElse("")
        val lastName = Option(userDetails.getOrElse("lastName", "").asInstanceOf[String]).getOrElse("")

        def nullStringCheck(name: String): String = {
            if (StringUtils.equalsIgnoreCase("null", name)) "" else name
        }

        val recipientName = nullStringCheck(firstName).concat(" ").concat(nullStringCheck(lastName)).trim
        val courseName = getCourseName(event.courseId)(metrics, config, cache, httpUtil)
        val dateFormatter = new SimpleDateFormat("yyyy-MM-dd")
        val related = getRelatedData(event, enrolledUser, assessedUser, userDetails, additionalProps, certName, courseName)(config)
        val eData = Map[String, AnyRef] (
            "issuedDate" -> dateFormatter.format(enrolledUser.issuedOn),
            "data" -> List(Map[String, AnyRef]("recipientName" -> recipientName, "recipientId" -> event.userId)),
            "criteria" -> Map[String, String]("narrative" -> certName),
            "svgTemplate" -> template.getOrElse("url", "").replace(config.cloudStoreBasePathPlaceholder, config.baseUrl+"/"+config.contentCloudStorageContainer),
            "oldId" -> enrolledUser.oldId,
            "templateId" -> template.getOrElse(config.identifier, ""),
            "userId" -> event.userId,
            "orgId" -> userDetails.getOrElse("rootOrgId", ""),
            "issuer" -> ScalaJsonUtil.deserialize[Map[String, AnyRef]](template.getOrElse(config.issuer, "{}")),
            "signatoryList" -> ScalaJsonUtil.deserialize[List[Map[String, AnyRef]]](template.getOrElse(config.signatoryList, "[]")),
            "courseName" -> courseName,
            "basePath" -> config.certBasePath,
            "related" ->  related,
            "name" -> certName,
            "tag" -> event.batchId
        ) ++ {if(attemptDetails != null && attemptDetails.nonEmpty) { if(attemptDetails.contains("attempt_count")) Map[String, AnyRef]("attempt_count" -> attemptDetails.getOrElse("attempt_count","")) else Map.empty[String, AnyRef] }
        else Map.empty[String, AnyRef]
        } ++ {if(attemptDetails != null && attemptDetails.nonEmpty) { if(attemptDetails.contains("attempt_id")) Map[String, AnyRef]("attempt_id" -> attemptDetails.getOrElse("attempt_id","")) else Map.empty[String, AnyRef] }
        else Map.empty[String, AnyRef]
        }

        ScalaJsonUtil.serialize(BEJobRequestEvent(edata = eData, `object` = EventObject(id = event.userId)))
    }
    def getLocationDetails(userDetails: Map[String, AnyRef], additionalProps: Map[String, List[String]]): Map[String, Any] = {
        if(additionalProps.getOrElse("location", List()).nonEmpty) {
            val userLocations = userDetails.getOrElse("userLocations", List()).asInstanceOf[List[Map[String, AnyRef]]].map(l => l.getOrElse("type", "").asInstanceOf[String] -> l.getOrElse("name", "").asInstanceOf[String]).toMap
            val locAdditionProps = additionalProps.getOrElse("location", List()).map(prop => prop -> userLocations.getOrElse(prop, null)).filter(p => null != p._2).toMap
            if(locAdditionProps.nonEmpty) Map("location" -> locAdditionProps) else Map()
        }else Map()
    }

    def getRelatedData(event: Event, enrolledUser: EnrolledUser, assessedUser: AssessedUser,
                       userDetails: Map[String, AnyRef], additionalProps: Map[String, List[String]], certName: String, courseName: String)(config: CollectionCertPreProcessorConfig): Map[String, Any] = {
        val userAdditionalProps = additionalProps.getOrElse(config.user, List()).filter(prop => userDetails.contains(prop)).map(prop => prop -> userDetails.getOrElse(prop, null)).toMap
        val locationProps = getLocationDetails(userDetails, additionalProps)
        val courseAdditionalProps: Map[String, Any] = if(additionalProps.getOrElse("course", List()).nonEmpty) Map("course" -> Map("name" -> courseName)) else Map()
        Map[String, Any]("batchId" -> event.batchId, "courseId" -> event.courseId, "type" -> certName) ++
          locationProps ++ enrolledUser.additionalProps ++ assessedUser.additionalProps ++ userAdditionalProps ++ courseAdditionalProps
    }
}
