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
 * Le relevé spark.
 *
 * <p>AU SCRAPE ET NON EN FOND : tout ce que spark expose est déjà calculé et mis en cache par lui,
 * {@code poll()} ne fait que lire un champ. C'est le collecteur le moins cher du plugin.
 *
 * <p>DEUX VERSIONS DE L'API COEXISTENT SUR CE RÉSEAU, et c'est ce qui commande toute l'écriture de
 * cette classe :
 *
 * <ul>
 *   <li>le <b>lobby</b> porte celle que Paper embarque, {@code 0.1-20240720} — {@code tps},
 *       {@code mspt}, {@code cpuProcess}, {@code cpuSystem}, {@code gc}, et rien d'autre ;
 *   <li>le <b>proxy</b> porte celle de spark 1.10.187, qui ajoute {@code memoryAllocation()} et
 *       {@code playerPing()}.
 * </ul>
 *
 * <p>D'où deux règles. <b>Les fenêtres se découvrent par {@code getWindows()}</b> au lieu d'être
 * nommées : {@code StatisticWindow.MemoryAllocation} n'existe pas côté Paper, et la seule mention
 * de cette classe suffisait à faire échouer le module avec un {@code NoClassDefFoundError} —
 * constaté. Et <b>les deux méthodes récentes passent par la réflexion</b>, ce qui est ici le
 * choix honnête plutôt qu'un contournement : la solution propre serait de compiler deux modules,
 * un par version d'API, pour deux métriques.
 */
public final class SparkCollector implements Collector {

	private final Platform plateforme;

	/** Absentes sur la version de Paper. Résolues une fois, à la déclaration. */
	private final Method methodeAllocation;
	private final Method methodePing;

	private Gauge tps;
	private Gauge tick;
	private Gauge cpu;
	private Gauge allocation;
	private Gauge gcTempsMoyen;
	private Gauge gcFrequence;
	private Gauge pingJoueurs;

	public SparkCollector(Platform plateforme) {
		this.plateforme = plateforme;
		this.methodeAllocation = methode("memoryAllocation");
		this.methodePing = methode("playerPing");
	}

	private static Method methode(String nom) {
		try {
			return Spark.class.getMethod(nom);
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	@Override
	public String nom() {
		return "spark";
	}

	@Override
	public String origine() {
		return "spark";
	}

	@Override
	public void declarer(MetricRegistry r) {
		tps = r.gauge("spark_tps", "TPS vu par spark. window = 5s|10s|1m|5m|15m.", "window");
		tick = r.gauge("spark_tick_duration_seconds",
				"Durée de tick vue par spark. quantile = min|mean|median|p95|max. Jauges figées "
						+ "sur les fenêtres de spark : pour un vrai histogramme, voir "
						+ "mc_tick_duration_seconds.",
				"window", "quantile");
		cpu = r.gauge("spark_cpu_ratio",
				"Charge processeur, de 0 à 1. source = process|system.", "source", "window");
		gcTempsMoyen = r.gauge("spark_gc_average_seconds",
				"Durée moyenne d'une collecte, par ramasse-miettes.", "gc");
		gcFrequence = r.gauge("spark_gc_average_frequency_seconds",
				"Temps moyen entre deux collectes. Court = le tas se remplit vite.", "gc");

		if (methodeAllocation != null) {
			allocation = r.gauge("spark_allocation_bytes_per_second",
					"Taux d'allocation mémoire. Ce n'est pas la mémoire OCCUPÉE qui fait les "
							+ "à-coups, c'est la vitesse à laquelle on en demande : chaque "
							+ "gigaoctet alloué finit en collecte, donc en micro-pause.",
					"window", "quantile");
		}
		if (methodePing != null) {
			pingJoueurs = r.gauge("spark_player_ping_seconds",
					"Ping des joueurs agrégé par spark. quantile = min|mean|median|p95|max.",
					"window", "quantile");
		}
		if (methodeAllocation == null || methodePing == null) {
			plateforme.info("collecteur spark — API ancienne : taux d'allocation et ping agrégé "
					+ "indisponibles, le reste est publié");
		}
	}

	@Override
	public void relever(MetricRegistry r) {
		Spark spark = spark();
		if (spark == null) {
			return;
		}

		DoubleStatistic<StatisticWindow.TicksPerSecond> statTps = spark.tps();
		if (statTps != null) {
			for (StatisticWindow.TicksPerSecond f : statTps.getWindows()) {
				tps.set(statTps.poll(f), etiquette(f));
			}
		}

		GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt = spark.mspt();
		if (mspt != null) {
			for (StatisticWindow.MillisPerTick f : mspt.getWindows()) {
				// spark rend des MILLISECONDES ; Prometheus veut des unités de base. Publier des
				// millisecondes ferait des graphiques justes et des alertes fausses, puisque
				// tout le reste du plugin est en secondes.
				quantiles(tick, mspt.poll(f), 1e-3, etiquette(f));
			}
		}

		cpuVers(spark.cpuProcess(), "process");
		cpuVers(spark.cpuSystem(), "system");

		Map<String, GarbageCollector> gc = spark.gc();
		if (gc != null) {
			gc.forEach((nom, g) -> {
				gcTempsMoyen.set(g.avgTime() / 1000.0, nom);
				gcFrequence.set(g.avgFrequency() / 1000.0, nom);
			});
		}

		reflechi(spark, methodeAllocation, allocation, 1.0);
		reflechi(spark, methodePing, pingJoueurs, 1e-3);
	}

	private void cpuVers(DoubleStatistic<StatisticWindow.CpuUsage> stat, String source) {
		if (stat == null) {
			return;
		}
		for (StatisticWindow.CpuUsage f : stat.getWindows()) {
			cpu.set(stat.poll(f), source, etiquette(f));
		}
	}

	/**
	 * Relève une statistique que seule l'API récente possède.
	 *
	 * <p>Seul l'APPEL est réfléchi : le type rendu, {@code GenericStatistic}, existe dans les deux
	 * versions, donc tout ce qui suit est du code normal et vérifié à la compilation.
	 */
	@SuppressWarnings("unchecked")
	private void reflechi(Spark spark, Method methode, Gauge cible, double facteur) {
		if (methode == null || cible == null) {
			return;
		}
		try {
			Object brut = methode.invoke(spark);
			if (!(brut instanceof GenericStatistic)) {
				return;
			}
			GenericStatistic<DoubleAverageInfo, ?> stat =
					(GenericStatistic<DoubleAverageInfo, ?>) brut;
			for (Enum<?> f : stat.getWindows()) {
				quantiles(cible, pollBrut(stat, f), facteur, etiquette((StatisticWindow) f));
			}
		} catch (ReflectiveOperationException | ClassCastException e) {
			plateforme.avertir("spark : " + methode.getName() + " illisible — " + e);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static DoubleAverageInfo pollBrut(GenericStatistic stat, Enum<?> fenetre) {
		return (DoubleAverageInfo) stat.poll(fenetre);
	}

	private void quantiles(Gauge g, DoubleAverageInfo info, double facteur, String fenetre) {
		if (info == null) {
			return;
		}
		g.set(facteur * info.min(), fenetre, "min");
		g.set(facteur * info.mean(), fenetre, "mean");
		g.set(facteur * info.median(), fenetre, "median");
		g.set(facteur * info.percentile95th(), fenetre, "p95");
		g.set(facteur * info.max(), fenetre, "max");
	}

	/**
	 * L'instance de spark, par les deux voies, et RÉSOLUE À CHAQUE RELEVÉ.
	 *
	 * <p>Pas de mise en cache : l'ordre de démarrage n'est garanti sur aucune des deux
	 * plateformes, et un spark rechargé à chaud rendrait une instance périmée. Le coût est une
	 * recherche dans une table toutes les quinze secondes.
	 *
	 * <p>Le {@code ServicesManager} d'abord, parce que c'est la voie de Paper, où spark est
	 * intégré au serveur et ne renseigne PAS {@code SparkProvider} — constaté.
	 */
	private Spark spark() {
		java.util.Optional<Spark> service = plateforme.service(Spark.class);
		if (service.isPresent()) {
			return service.get();
		}
		try {
			return SparkProvider.get();
		} catch (Throwable t) {
			// IllegalStateException quand spark est là mais pas encore prêt : état transitoire
			// du démarrage, pas une erreur. On ne publie rien ce tour-ci.
			return null;
		}
	}

	/** « PT10S » ne se lit pas dans Grafana. « 10s » si. */
	private static String etiquette(StatisticWindow f) {
		long s = f.length().getSeconds();
		return s >= 60 && s % 60 == 0 ? (s / 60) + "m" : s + "s";
	}
}
