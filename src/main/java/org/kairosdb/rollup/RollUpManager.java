package org.kairosdb.rollup;


import com.google.inject.name.Named;
import org.kairosdb.core.datastore.Duration;
import org.kairosdb.core.datastore.KairosDatastore;
import org.kairosdb.core.datastore.TimeUnit;
import org.kairosdb.core.exception.KairosDBException;
import org.kairosdb.core.http.rest.QueryException;
import org.kairosdb.core.scheduler.KairosDBJob;
import org.kairosdb.core.scheduler.KairosDBScheduler;
import org.quartz.*;
import org.quartz.impl.JobDetailImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.kairosdb.util.Preconditions.checkNotNullOrEmpty;
import static org.quartz.CalendarIntervalScheduleBuilder.calendarIntervalSchedule;
import static org.quartz.TriggerBuilder.newTrigger;

// todo need test
public class RollUpManager implements KairosDBJob
{
	public static final Logger logger = LoggerFactory.getLogger(RollUpManager.class);
	public static final String SCHEDULE = "kairosdb.rollup.rollup_manager_schedule";

	private final String schedule;
	private final RollUpTasksStore taskStore;
	private final KairosDBScheduler scheduler;
	private final Map<String, Long> taskIdToTimeMap = new HashMap<String, Long>();
	private final KairosDatastore dataStore;

	private long tasksLastModified;

	@Inject
	public RollUpManager(
			@Named(SCHEDULE) String schedule,
			RollUpTasksStore taskStore, KairosDBScheduler scheduler, KairosDatastore dataStore)
	{
		this.schedule = checkNotNullOrEmpty(schedule);
		this.taskStore = checkNotNull(taskStore);
		this.scheduler = checkNotNull(scheduler);
		this.dataStore = checkNotNull(dataStore);
	}

	@Override
	public Trigger getTrigger()
	{
		return newTrigger()
				.withIdentity(this.getClass().getSimpleName())
				.withSchedule(CronScheduleBuilder.cronSchedule(schedule))
				.build();
	}

	@SuppressWarnings("unchecked")
	@Override
	public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException
	{
		//		logger.info("Rollup manager running");
		try
		{
			long lastModified = taskStore.lastModifiedTime();
			if (lastModified > tasksLastModified)
			{
				logger.info("Reading rollup task config");
				List<RollupTask> updatedTasks = taskStore.read();

				scheduleNewTasks(updatedTasks);
				unscheduledRemovedTasks(updatedTasks);
				updateScheduledTasks(updatedTasks);

				tasksLastModified = lastModified;
			}
		}
		catch (RollUpException e)
		{
			logger.error("Roll up manager failure.", e);
		}
		catch (QueryException e)
		{
			logger.error("Roll up manager failure.", e);
		}
	}

	@Override
	public void interrupt()
	{
		// todo?
	}

	private void updateScheduledTasks(List<RollupTask> updatedTasks)
	{
		for (RollupTask task : updatedTasks)
		{
			//			Long timestamp = taskIdToTimeMap.get(task.getId());
			//			if (neverScheduledOrChanged(task, timestamp))
			//			{
				try
				{
					scheduler.cancel(getJobKey(task));
				}
				catch (KairosDBException e)
				{
					logger.error("Could not cancel roll up task job " + task, e);
					continue;
				}

				try
				{
					logger.info("Updating schedule for rollup " + task.getName());
					JobDetailImpl jobDetail = createJobDetail(task, dataStore);
					Trigger trigger = createTrigger(task);
					scheduler.schedule(jobDetail, trigger);
					logger.info("Roll-up task " + jobDetail.getFullName() + " scheduled. Next execution time " + trigger.getNextFireTime());
				}
				catch (KairosDBException e)
				{
					logger.error("Could not schedule roll up task job " + task, e);
					continue;
				}

			//				taskIdToTimeMap.put(task.getId(), task.getTimestamp());
			}
		//		}
	}

	private boolean neverScheduledOrChanged(RollupTask task, Long timestamp)
	{
		return timestamp != null && task.getTimestamp() > timestamp;
	}

	private void unscheduledRemovedTasks(List<RollupTask> tasks)
	{
		// todo more elegant way to do this
		Iterator<String> iterator = taskIdToTimeMap.keySet().iterator();
		while (iterator.hasNext())
		{
			String id = iterator.next();

			RollupTask currentTask = null;
			RollupTask foundTask = null;
			for (RollupTask task : tasks)
			{
				currentTask = task;
				if (task.getId().equals(id))
				{
					foundTask = task;
					break;
				}
			}

			if (foundTask == null)
			{
				try
				{
					logger.info("Cancelling rollup " + currentTask.getName());
					iterator.remove();
					scheduler.cancel(getJobKey(foundTask));
				}
				catch (KairosDBException e)
				{
					logger.error("Could not cancel roll up task job " + currentTask.getName(), e);
				}
			}
		}
	}

	private void scheduleNewTasks(List<RollupTask> tasks)
	{
		for (RollupTask task : tasks)
		{
			if (!taskIdToTimeMap.containsKey(task.getId()))
			{
				try
				{
					logger.info("Scheduling rollup " + task.getName());
					Trigger trigger = createTrigger(task);
					JobDetailImpl jobDetail = createJobDetail(task, dataStore);
					scheduler.schedule(jobDetail, trigger);
					taskIdToTimeMap.put(task.getId(), task.getTimestamp());
					logger.info("Roll-up task " + jobDetail.getFullName() + " scheduled. Next execution time " + trigger.getNextFireTime());
				}
				catch (KairosDBException e)
				{
					logger.error("Failed to schedule new roll up task job " + task, e);
				}
			}
		}
	}

	private static JobKey getJobKey(RollupTask task)
	{
		return new JobKey(task.getId(), RollUpJob.class.getSimpleName());
	}

	private static JobDetailImpl createJobDetail(RollupTask task, KairosDatastore dataStore)
	{
		JobDetailImpl jobDetail = new JobDetailImpl();
		jobDetail.setJobClass(RollUpJob.class);
		jobDetail.setKey(getJobKey(task));

		JobDataMap map = new JobDataMap();
		map.put("task", task);
		map.put("datastore", dataStore);
		jobDetail.setJobDataMap(map);
		return jobDetail;
	}

	@SuppressWarnings("ConstantConditions")
	private static Trigger createTrigger(RollupTask task)
	{
		Duration executionInterval = task.getExecutionInterval();
		return newTrigger()
				.withIdentity(task.getId(), task.getClass().getSimpleName())
				.startAt(DateBuilder.futureDate((int) executionInterval.getValue(), toIntervalUnit(executionInterval.getUnit())))
				.withSchedule(calendarIntervalSchedule()
						.withInterval((int) executionInterval.getValue(), toIntervalUnit(executionInterval.getUnit())))
				.build();
	}

	private static DateBuilder.IntervalUnit toIntervalUnit(TimeUnit unit)
	{
		switch (unit)
		{
			case MILLISECONDS:
				return DateBuilder.IntervalUnit.MILLISECOND;
			case SECONDS:
				return DateBuilder.IntervalUnit.SECOND;
			case MINUTES:
				return DateBuilder.IntervalUnit.MINUTE;
			case HOURS:
				return DateBuilder.IntervalUnit.HOUR;
			case DAYS:
				return DateBuilder.IntervalUnit.DAY;
			case WEEKS:
				return DateBuilder.IntervalUnit.WEEK;
			case MONTHS:
				return DateBuilder.IntervalUnit.MONTH;
			case YEARS:
				return DateBuilder.IntervalUnit.YEAR;
			default:
				checkState(false, "Invalid time unit" + unit);
				return null;
		}
	}

}