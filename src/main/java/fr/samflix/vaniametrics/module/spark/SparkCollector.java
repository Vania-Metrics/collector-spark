package fr.samflix.vaniametrics.module.spark;

import java.lang.reflect.Method;
import java.util.Map;

import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.gc.GarbageCollector;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;

import fr.samflix.vaniametrics.api.Collector;
import fr.samflix.vaniametrics.api.Gauge;
import fr.samflix.vaniametrics.api.MetricRegistry;
import fr.samflix.vaniametrics.api.Platform;

/**
 * The spark poll.
 *
 * <p>Scrape mode, not background: everything spark exposes is already computed and cached by it,
 * {@code poll()} just reads a field. This is the cheapest collector in the plugin.
 *
 * <p>Two versions of the API coexist on this network, and that drives how this class is written:
 *
 * <ul>
 *   <li>the <b>lobby</b> carries the one Paper embeds, {@code 0.1-20240720} — {@code tps},
 *       {@code mspt}, {@code cpuProcess}, {@code cpuSystem}, {@code gc}, and nothing else;
 *   <li>the <b>proxy</b> carries spark 1.10.187's, which adds {@code memoryAllocation()} and
 *       {@code playerPing()}.
 * </ul>
 *
 * <p>Hence two rules. <b>Windows are discovered via {@code getWindows()}</b> instead of being
 * named: {@code StatisticWindow.MemoryAllocation} doesn't exist on the Paper side, and merely
 * referencing that class was enough to make the module fail with a {@code NoClassDefFoundError} —
 * observed. And <b>the two recent methods go through reflection</b>, which here is the honest
 * choice rather than a workaround: the clean solution would be to compile two modules, one per API
 * version, for two metrics.
 */
public final class SparkCollector implements Collector {

	private final Platform platform;

	/** Absent on the Paper version of the API. Resolved once, at declare time. */
	private final Method allocationMethod;
	private final Method pingMethod;

	private Gauge tps;
	private Gauge tick;
	private Gauge cpu;
	private Gauge allocation;
	private Gauge gcAverageTime;
	private Gauge gcFrequency;
	private Gauge playerPing;

	public SparkCollector(Platform platform) {
		this.platform = platform;
		this.allocationMethod = method("memoryAllocation");
		this.pingMethod = method("playerPing");
	}

	private static Method method(String name) {
		try {
			return Spark.class.getMethod(name);
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	@Override
	public String name() {
		return "spark";
	}

	@Override
	public String source() {
		return "spark";
	}

	@Override
	public void declare(MetricRegistry r) {
		tps = r.gauge("spark_tps", "TPS as seen by spark. window = 5s|10s|1m|5m|15m.", "window");
		tick = r.gauge("spark_tick_duration_seconds",
				"Tick duration as seen by spark. quantile = min|mean|median|p95|max. Gauges "
						+ "frozen on spark's windows: for a real histogram, see "
						+ "mc_tick_duration_seconds.",
				"window", "quantile");
		cpu = r.gauge("spark_cpu_ratio",
				"CPU load, from 0 to 1. source = process|system.", "source", "window");
		gcAverageTime = r.gauge("spark_gc_average_seconds",
				"Average duration of a collection, per garbage collector.", "gc");
		gcFrequency = r.gauge("spark_gc_average_frequency_seconds",
				"Average time between two collections. Short = the heap fills up fast.", "gc");

		if (allocationMethod != null) {
			allocation = r.gauge("spark_allocation_bytes_per_second",
					"Memory allocation rate. It's not the memory that's OCCUPIED that causes "
							+ "stutters, it's the rate at which it's requested: every gigabyte "
							+ "allocated ends up collected, hence a micro-pause.",
					"window", "quantile");
		}
		if (pingMethod != null) {
			playerPing = r.gauge("spark_player_ping_seconds",
					"Player ping aggregated by spark. quantile = min|mean|median|p95|max.",
					"window", "quantile");
		}
		if (allocationMethod == null || pingMethod == null) {
			platform.info("spark collector — old API: allocation rate and aggregated ping "
					+ "unavailable, the rest is published");
		}
	}

	@Override
	public void collect(MetricRegistry r) {
		Spark spark = spark();
		if (spark == null) {
			return;
		}

		DoubleStatistic<StatisticWindow.TicksPerSecond> tpsStat = spark.tps();
		if (tpsStat != null) {
			for (StatisticWindow.TicksPerSecond w : tpsStat.getWindows()) {
				tps.set(tpsStat.poll(w), label(w));
			}
		}

		GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt = spark.mspt();
		if (mspt != null) {
			for (StatisticWindow.MillisPerTick w : mspt.getWindows()) {
				// spark returns MILLISECONDS; Prometheus wants base units. Publishing
				// milliseconds would make graphs correct and alerts wrong, since the rest of
				// the plugin is in seconds.
				quantiles(tick, mspt.poll(w), 1e-3, label(w));
			}
		}

		cpuInto(spark.cpuProcess(), "process");
		cpuInto(spark.cpuSystem(), "system");

		Map<String, GarbageCollector> gc = spark.gc();
		if (gc != null) {
			gc.forEach((name, g) -> {
				gcAverageTime.set(g.avgTime() / 1000.0, name);
				gcFrequency.set(g.avgFrequency() / 1000.0, name);
			});
		}

		reflective(spark, allocationMethod, allocation, 1.0);
		reflective(spark, pingMethod, playerPing, 1e-3);
	}

	private void cpuInto(DoubleStatistic<StatisticWindow.CpuUsage> stat, String source) {
		if (stat == null) {
			return;
		}
		for (StatisticWindow.CpuUsage w : stat.getWindows()) {
			cpu.set(stat.poll(w), source, label(w));
		}
	}

	/**
	 * Polls a statistic that only the recent API has.
	 *
	 * <p>Only the CALL is reflective: the returned type, {@code GenericStatistic}, exists in both
	 * versions, so everything after that is normal, compile-checked code.
	 */
	@SuppressWarnings("unchecked")
	private void reflective(Spark spark, Method method, Gauge target, double factor) {
		if (method == null || target == null) {
			return;
		}
		try {
			Object raw = method.invoke(spark);
			if (!(raw instanceof GenericStatistic)) {
				return;
			}
			GenericStatistic<DoubleAverageInfo, ?> stat =
					(GenericStatistic<DoubleAverageInfo, ?>) raw;
			for (Enum<?> w : stat.getWindows()) {
				quantiles(target, pollRaw(stat, w), factor, label((StatisticWindow) w));
			}
		} catch (ReflectiveOperationException | ClassCastException e) {
			platform.warn("spark: " + method.getName() + " unreadable — " + e);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static DoubleAverageInfo pollRaw(GenericStatistic stat, Enum<?> window) {
		return (DoubleAverageInfo) stat.poll(window);
	}

	private void quantiles(Gauge g, DoubleAverageInfo info, double factor, String window) {
		if (info == null) {
			return;
		}
		g.set(factor * info.min(), window, "min");
		g.set(factor * info.mean(), window, "mean");
		g.set(factor * info.median(), window, "median");
		g.set(factor * info.percentile95th(), window, "p95");
		g.set(factor * info.max(), window, "max");
	}

	/**
	 * The spark instance, by either path, RESOLVED ON EVERY POLL.
	 *
	 * <p>No caching: start order is guaranteed on neither platform, and a hot-reloaded spark
	 * would leave a stale instance cached. The cost is a table lookup every fifteen seconds.
	 *
	 * <p>{@code ServicesManager} first, because that's Paper's path, where spark is embedded in
	 * the server and does NOT populate {@code SparkProvider} — observed.
	 */
	private Spark spark() {
		java.util.Optional<Spark> service = platform.service(Spark.class);
		if (service.isPresent()) {
			return service.get();
		}
		try {
			return SparkProvider.get();
		} catch (Throwable t) {
			// IllegalStateException when spark is there but not ready yet: a transient
			// startup state, not an error. Nothing is published this round.
			return null;
		}
	}

	/** "PT10S" doesn't read well in Grafana. "10s" does. */
	private static String label(StatisticWindow f) {
		long s = f.length().getSeconds();
		return s >= 60 && s % 60 == 0 ? (s / 60) + "m" : s + "s";
	}
}
