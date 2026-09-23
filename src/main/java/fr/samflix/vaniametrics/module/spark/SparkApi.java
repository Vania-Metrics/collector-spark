package fr.samflix.vaniametrics.module.spark;

/**
 * "Is spark there?", asked without naming any platform.
 *
 * <p>This class exists because of a real crash. The check used to live on {@code SparkPaper}, as
 * a static method, and the Velocity entry point called it — which forced {@code SparkPaper} to
 * load, and with it its superclass {@code JavaPlugin}, and with that Bukkit, on a proxy that has
 * none:
 *
 * <pre>
 * Couldn't pass ProxyInitializeEvent to vaniametrics-spark
 * java.lang.NoClassDefFoundError: org/bukkit/plugin/java/JavaPlugin
 * </pre>
 *
 * <p>The rule to take from this, and it holds for any dual-platform jar: <b>a class shared by
 * both entry points must not name Bukkit or Velocity.</b> A plain static reference to a class is
 * enough to load it, well before anything on it is called.
 */
final class SparkApi {

	private SparkApi() {}

	/**
	 * Is spark's API loadable?
	 *
	 * <p>We look for neither a plugin named "spark" nor an instance: Paper has embedded spark in
	 * the server since 1.21 and it doesn't appear in any plugin list, even though its API is
	 * there. Whether an instance is ready is the collector's job, resolved on every poll.
	 */
	static boolean isPresent() {
		try {
			Class.forName("me.lucko.spark.api.Spark", false, SparkApi.class.getClassLoader());
			return true;
		} catch (Throwable t) {
			return false;
		}
	}
}
