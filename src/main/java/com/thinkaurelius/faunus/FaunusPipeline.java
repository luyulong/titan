package com.thinkaurelius.faunus;

import com.thinkaurelius.faunus.mapreduce.FaunusCompiler;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Query;
import com.tinkerpop.blueprints.Vertex;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.ToolRunner;
import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static com.tinkerpop.blueprints.Direction.BOTH;
import static com.tinkerpop.blueprints.Direction.IN;
import static com.tinkerpop.blueprints.Direction.OUT;


/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class FaunusPipeline {

    public static final String VERTEX_STATE_ERROR = "The compiler is currently in vertex state";
    public static final String EDGE_STATE_ERROR = "The compiler is currently in edge state";
    public static final String PIPELINE_IS_LOCKED = "No more steps are possible as pipeline is locked";

    private final FaunusCompiler compiler;
    private final JobState state;

    private Query.Compare opposite(final Query.Compare compare) {
        if (compare.equals(Query.Compare.EQUAL))
            return Query.Compare.NOT_EQUAL;
        else if (compare.equals(Query.Compare.NOT_EQUAL))
            return Query.Compare.EQUAL;
        else if (compare.equals(Query.Compare.GREATER_THAN))
            return Query.Compare.LESS_THAN_EQUAL;
        else if (compare.equals(Query.Compare.GREATER_THAN_EQUAL))
            return Query.Compare.LESS_THAN;
        else if (compare.equals(Query.Compare.LESS_THAN))
            return Query.Compare.GREATER_THAN_EQUAL;
        else
            return Query.Compare.GREATER_THAN;
    }

    private class JobState {
        private Class<? extends Element> elementType;
        private String property;
        private int step = -1;
        private boolean locked = false;
        private Map<String, Integer> namedSteps = new HashMap<String, Integer>();

        public JobState set(Class<? extends Element> elementType) {
            if (!elementType.equals(Vertex.class) && !elementType.equals(Edge.class))
                throw new RuntimeException("The element class type must be either Vertex or Edge");

            this.elementType = elementType;
            return this;
        }

        public Class<? extends Element> getElementType() {
            return this.elementType;
        }

        public boolean atVertex() {
            return this.elementType.equals(Vertex.class);
        }

        public JobState set(final String property) {
            this.property = property;
            return this;
        }

        public String popProperty() {
            final String temp = this.property;
            this.property = null;
            return temp;
        }

        public int incrStep() {
            return this.step++;
        }

        public void checkLocked() {
            if (this.locked) throw new IllegalStateException(PIPELINE_IS_LOCKED);
        }

        public boolean isLocked() {
            return this.locked;
        }

        public void lock() {
            this.locked = true;
        }

        public void addStep(final String name) {
            if (this.step == -1)
                throw new RuntimeException("There is no previous step to name");

            this.namedSteps.put(name, this.step);
        }

        public int getStep(final String name) {
            final Integer i = this.namedSteps.get(name);
            if (null == i)
                throw new IllegalStateException("There is no step identified by: " + name);
            else
                return i;
        }
    }

    public FaunusPipeline(final String jobScript, final Configuration conf) {
        this.compiler = new FaunusCompiler(jobScript, conf);
        this.state = new JobState();
    }


    //////// TRANSFORMS

    public FaunusPipeline _() {
        this.state.checkLocked();
        this.compiler._();
        return this;
    }

    public FaunusPipeline transform(final String closure) throws IOException {
        this.state.checkLocked();
        this.compiler.transform(this.state.getElementType(), closure);
        this.state.lock();
        return this;
    }

    public FaunusPipeline V() {
        this.state.checkLocked();
        this.state.set(Vertex.class);
        this.state.incrStep();
        this.compiler.verticesMap();
        return this;
    }

    public FaunusPipeline E() {
        this.state.checkLocked();
        this.state.set(Edge.class);
        this.state.incrStep();
        this.compiler.edgesMap();
        return this;
    }

    public FaunusPipeline v(final long... ids) {
        this.state.checkLocked();
        this.state.set(Vertex.class);
        this.state.incrStep();
        this.compiler.vertexMap(ids);
        return this;
    }

    public FaunusPipeline out(final String... labels) throws IOException {
        this.state.checkLocked();
        this.state.incrStep();
        if (this.state.atVertex()) {
            this.compiler.verticesVerticesMapReduce(OUT, labels);
            return this;
        } else
            throw new RuntimeException("This step can not follow an edge-based step");
    }

    public FaunusPipeline in(final String... labels) throws IOException {
        this.state.checkLocked();
        this.state.incrStep();
        if (this.state.atVertex()) {
            this.compiler.verticesVerticesMapReduce(IN, labels);
            return this;
        } else
            throw new RuntimeException("This step can not follow an edge-based step");
    }

    public FaunusPipeline both(final String... labels) throws IOException {
        this.state.checkLocked();
        this.state.incrStep();
        if (this.state.atVertex()) {
            this.compiler.verticesVerticesMapReduce(BOTH, labels);
            return this;
        } else
            throw new RuntimeException("This step can not follow an edge-based step");
    }

    public FaunusPipeline outE(final String... labels) throws IOException {
        this.state.checkLocked();
        this.state.incrStep();
        if (this.state.atVertex()) {
            this.compiler.verticesEdgesMapReduce(OUT, labels);
            this.state.set(Edge.class);
            return this;
        } else
            throw new RuntimeException("This step can not follow an edge-based step");
    }

    public FaunusPipeline inE(final String... labels) throws IOException {
        this.state.checkLocked();
        this.state.incrStep();
        if (this.state.atVertex()) {
            this.compiler.verticesEdgesMapReduce(IN, labels);
            this.state.set(Edge.class);
            return this;
        } else
            throw new RuntimeException("This step can not follow an edge-based step");
    }

    public FaunusPipeline bothE(final String... labels) throws IOException {
        this.state.checkLocked();
        this.state.incrStep();
        if (this.state.atVertex()) {
            this.compiler.verticesEdgesMapReduce(BOTH, labels);
            this.state.set(Edge.class);
            return this;
        } else
            throw new RuntimeException("This step can not follow an edge-based step");
    }

    public FaunusPipeline outV() throws IOException {
        this.state.checkLocked();
        this.state.incrStep();
        if (!this.state.atVertex()) {
            this.compiler.edgesVerticesMap(OUT);
            this.state.set(Vertex.class);
            return this;
        } else
            throw new RuntimeException("This step can not follow a vertex-based step");
    }

    public FaunusPipeline inV() throws IOException {
        this.state.checkLocked();
        this.state.incrStep();
        if (!this.state.atVertex()) {
            this.compiler.edgesVerticesMap(IN);
            this.state.set(Vertex.class);
            return this;
        } else
            throw new RuntimeException("This step can not follow a vertex-based step");
    }

    public FaunusPipeline property(final String key) throws IOException {
        this.state.checkLocked();
        //this.compiler.propertyMap(this.state.getElementType(), key);
        this.state.set(key);
        return this;
    }

    public FaunusPipeline label() throws IOException {
        this.state.checkLocked();
        if (!this.state.atVertex()) {
            //this.compiler.propertyMap(this.state.getElementType(), Tokens.LABEL);
            this.state.set(Tokens.LABEL);
            return this;
        } else
            throw new RuntimeException("This step can not follow a vertex-based step");
    }

    public FaunusPipeline path() throws IOException {
        this.state.checkLocked();
        this.compiler.pathMap(this.state.getElementType());
        this.state.lock();
        return this;
    }

    public FaunusPipeline select(final int... steps) throws IOException {
        this.state.checkLocked();
        //this.compiler.select(this.state.getElementType(), steps);
        this.state.lock();
        return this;
    }

    //////// FILTERS

    public FaunusPipeline filter(final String closure) {
        this.state.checkLocked();
        this.compiler.filterMap(this.state.getElementType(), closure);
        return this;
    }

    public FaunusPipeline dedup() {
        this.state.checkLocked();
        this.compiler.duplicateFilterMap(this.state.getElementType());
        return this;
    }

    public FaunusPipeline has(final String key, final Query.Compare compare, final Object... values) {
        this.state.checkLocked();
        this.compiler.propertyFilterMap(this.state.getElementType(), false, key, compare, values);
        return this;
    }

    public FaunusPipeline hasNot(final String key, final Query.Compare compare, final Object... values) {
        this.state.checkLocked();
        return this.has(key, this.opposite(compare), values);
    }

    public FaunusPipeline has(final String key, final Object... values) {
        this.state.checkLocked();
        return this.has(key, Query.Compare.EQUAL, values);
    }

    public FaunusPipeline hasNot(final String key, final Object... values) {
        this.state.checkLocked();
        return this.has(key, Query.Compare.NOT_EQUAL, values);
    }

    public FaunusPipeline groupCount() throws IOException {
        this.state.checkLocked();
        this.compiler.valueDistribution(this.state.getElementType(), this.state.popProperty());
        return this;
    }

    public FaunusPipeline groupCount(final String keyClosure, final String valueClosure) throws IOException {
        this.state.checkLocked();
        this.compiler.groupCountMapReduce(this.state.getElementType(), keyClosure, valueClosure);
        return this;
    }

    public FaunusPipeline interval(final String key, final Object startValue, final Object endValue) {
        this.state.checkLocked();
        this.compiler.intervalFilterMap(this.state.getElementType(), false, key, startValue, endValue);
        return this;
    }

    public FaunusPipeline back(final String step) throws IOException {
        this.state.checkLocked();
        this.compiler.backFilterMapReduce(this.state.getElementType(), this.state.getStep(step));
        return this;
    }

    //////// SIDEEFFECTS

    public FaunusPipeline as(final String name) {
        this.state.checkLocked();
        this.state.addStep(name);
        return this;
    }


    public FaunusPipeline linkIn(final String step, final String label, final String mergeWeightKey) throws IOException {
        this.state.checkLocked();
        this.compiler.linkMapReduce(this.state.getStep(step), IN, label, mergeWeightKey);
        return this;
    }

    public FaunusPipeline linkIn(final String step, final String label) throws IOException {
        return this.linkIn(step, label, null);
    }

    public FaunusPipeline linkOut(final String step, final String label, final String mergeWeightKey) throws IOException {
        this.state.checkLocked();
        this.compiler.linkMapReduce(this.state.getStep(step), OUT, label, mergeWeightKey);
        return this;
    }

    public FaunusPipeline linkOut(final String step, final String label) throws IOException {
        return this.linkOut(step, label, null);
    }


    private FaunusPipeline commit(final Tokens.Action action) throws IOException {
        this.state.checkLocked();
        if (this.state.atVertex())
            this.compiler.commitVerticesMapReduce(action);
        else
            this.compiler.commitEdgesMap(action);
        return this;
    }

    public FaunusPipeline drop() throws IOException {
        return this.commit(Tokens.Action.DROP);
    }

    public FaunusPipeline keep() throws IOException {
        return this.commit(Tokens.Action.KEEP);
    }

    /////////////// UTILITIES

    public FaunusPipeline count() throws IOException {
        this.state.checkLocked();
        this.compiler.countMapReduce(this.state.getElementType());
        this.state.lock();
        return this;
    }

    public FaunusPipeline done() throws IOException {
        if (!this.state.isLocked()) {
            final String property = this.state.popProperty();
            if (null != property) {
                this.compiler.propertyMap(this.state.getElementType(), property);
            }
            this.state.lock();
        }
        return this;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 2) {
            System.out.println("Faunus: A Library of Graph-Based Hadoop Tools");
            System.out.println("FaunusPipeline Usage:");
            System.out.println("  arg1: faunus configuration location (optional - defaults to bin/faunus.properties)");
            System.out.println("  arg2: faunus script: g.V.step().step()...");
            System.exit(-1);
        }

        final String script;
        final String file;
        final java.util.Properties properties = new java.util.Properties();
        if (args.length == 1) {
            script = args[0];
            file = "bin/faunus.properties";
        } else {
            file = args[0];
            script = args[1];
        }
        properties.load(new FileInputStream(file));


        final Configuration conf = new Configuration();
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            conf.set(entry.getKey().toString(), entry.getValue().toString());
        }

        final FaunusPipeline faunusPipeline = new FaunusPipeline(script, conf);
        final GroovyScriptEngineImpl scriptEngine = new GroovyScriptEngineImpl();
        scriptEngine.eval("IN=" + Direction.class.getName() + ".IN");
        scriptEngine.eval("OUT=" + Direction.class.getName() + ".OUT");
        scriptEngine.eval("BOTH=" + Direction.class.getName() + ".BOTH");
        scriptEngine.eval("eq=" + Query.Compare.class.getName() + ".EQUAL");
        scriptEngine.eval("neq=" + Query.Compare.class.getName() + ".NOT_EQUAL");
        scriptEngine.eval("lt=" + Query.Compare.class.getName() + ".LESS_THAN");
        scriptEngine.eval("lte=" + Query.Compare.class.getName() + ".LESS_THAN_EQUAL");
        scriptEngine.eval("gt=" + Query.Compare.class.getName() + ".GREATER_THAN");
        scriptEngine.eval("gte=" + Query.Compare.class.getName() + ".GREATER_THAN_EQUAL");

        scriptEngine.put("g", faunusPipeline);
        FaunusPipeline pipeline = ((FaunusPipeline) scriptEngine.eval(script)).done();
        FaunusCompiler compiler = pipeline.compiler;
        compiler.completeSequence();
        int result = ToolRunner.run(compiler, args);
        System.exit(result);
    }
}
