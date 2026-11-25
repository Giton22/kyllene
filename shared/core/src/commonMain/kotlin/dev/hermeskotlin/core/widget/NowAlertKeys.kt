package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.widget.ScheduledWidgetAssembler.failureStamp

/**
 * The dismissal keys for the Now widget's clearable alerts (docs/WIDGETS-SPEC.md D27): a key names
 * one failure fingerprint, not just its source, so dismissing an old failed job hides it only until
 * the job fails again on a later run (a new [CronJob.failureStamp]) or a task changes status. The
 * Scheduled job page and the widget's dismiss button compute the same keys the reducer filters on.
 */
object NowAlertKeys {

    /** A cron problem or a bot's failing routine: the job plus its newest failure stamp. */
    fun cron(job: CronJob): String = "cron:${job.id}:${job.failureStamp}"

    /** A blocked/review task: the board, the task and the status word being dismissed. */
    fun kanban(boardSlug: String?, task: KanbanTask): String = "kanban:${boardSlug.orEmpty()}:${task.id}:${task.status}"
}