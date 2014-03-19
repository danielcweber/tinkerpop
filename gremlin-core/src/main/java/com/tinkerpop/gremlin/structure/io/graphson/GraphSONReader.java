package com.tinkerpop.gremlin.structure.io.graphson;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.tinkerpop.gremlin.structure.Direction;
import com.tinkerpop.gremlin.structure.Edge;
import com.tinkerpop.gremlin.structure.Element;
import com.tinkerpop.gremlin.structure.Graph;
import com.tinkerpop.gremlin.structure.Vertex;
import com.tinkerpop.gremlin.structure.io.GraphReader;
import com.tinkerpop.gremlin.structure.util.batch.BatchGraph;
import com.tinkerpop.gremlin.util.function.QuintFunction;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class GraphSONReader implements GraphReader {
    private final Graph g;
    private final ObjectMapper mapper;

    public GraphSONReader(final Graph g, final ObjectMapper mapper) {
        this.g = g;
        this.mapper = mapper;
    }

    @Override
    public void readGraph(final InputStream inputStream) throws IOException {
        final JsonFactory factory = mapper.getFactory();
        final JsonParser parser = factory.createParser(inputStream);

        // todo: configurable batchs ize
        final BatchGraph graph = new BatchGraph.Builder<>(g)
                .bufferSize(10000).build();

        if (parser.nextToken() != JsonToken.START_OBJECT)
            throw new IOException("Expected data to start with an Object");

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            final String fieldName = parser.getCurrentName();
            parser.nextToken();

            if (fieldName.equals(GraphSONModule.TOKEN_PROPERTIES)) {
                final Map<String,Object> graphProperties = parser.readValueAs(new TypeReference<Map<String,Object>>(){});
                if (g.getFeatures().graph().supportsMemory())
                    graphProperties.entrySet().forEach(entry-> g.memory().set(entry.getKey(), entry.getValue()));
            } else if (fieldName.equals(GraphSONModule.TOKEN_VERTICES)) {
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    final Map<String,Object> vertexData = parser.readValueAs(new TypeReference<Map<String, Object>>() { });
                    final Map<String, Map<String, Object>> properties = (Map<String,Map<String, Object>>) vertexData.get(GraphSONModule.TOKEN_PROPERTIES);
                    final Object[] propsAsArray = Stream.concat(properties.entrySet().stream().flatMap(e->Stream.of(e.getKey(), e.getValue().get("value"))),
                            Stream.of(Element.LABEL, vertexData.get(GraphSONModule.TOKEN_LABEL), Element.ID, vertexData.get(GraphSONModule.TOKEN_ID))).toArray();
                    graph.addVertex(propsAsArray);
                }
            } else if (fieldName.equals(GraphSONModule.TOKEN_EDGES)) {
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    final Map<String,Object> edgeData = parser.readValueAs(new TypeReference<Map<String, Object>>() {});
                    final Map<String, Map<String, Object>> properties = (Map<String,Map<String, Object>>) edgeData.get(GraphSONModule.TOKEN_PROPERTIES);
                    final Object[] propsAsArray = Stream.concat(properties.entrySet().stream().flatMap(e -> Stream.of(e.getKey(), e.getValue().get("value"))),
                            Stream.of(Element.ID, edgeData.get(GraphSONModule.TOKEN_ID))).toArray();
                    final Vertex vOut = graph.v(edgeData.get(GraphSONModule.TOKEN_OUT));
                    final Vertex vIn = graph.v(edgeData.get(GraphSONModule.TOKEN_IN));
                    vOut.addEdge(edgeData.get(GraphSONModule.TOKEN_LABEL).toString(), vIn, propsAsArray);
                }
            } else {
                // todo: invalidstateexception?  what was done in kryo/graphml? consistent?
            }
        }

        graph.tx().commit();
        parser.close();
    }

    @Override
    public Vertex readVertex(final InputStream inputStream,
                             final BiFunction<Object, Object[], Vertex> vertexMaker) throws IOException {
        final Map<String,Object> vertexData = mapper.readValue(inputStream, new TypeReference<Map<String,Object>>(){});
        final Map<String, Map<String, Object>> properties = (Map<String,Map<String, Object>>) vertexData.get(GraphSONModule.TOKEN_PROPERTIES);
        final Object[] propsAsArray = Stream.concat(properties.entrySet().stream().flatMap(e->Stream.of(e.getKey(), e.getValue().get("value"))),
                Stream.of(Element.LABEL, vertexData.get(GraphSONModule.TOKEN_LABEL))).toArray();
        return vertexMaker.apply(vertexData.get(GraphSONModule.TOKEN_ID), propsAsArray);
    }

    @Override
    public Vertex readVertex(final InputStream inputStream, final Direction direction,
                             final BiFunction<Object, Object[], Vertex> vertexMaker,
                             final QuintFunction<Object, Object, Object, String, Object[], Edge> edgeMaker) throws IOException {
        final Map<String,Object> vertexData = mapper.readValue(inputStream, new TypeReference<Map<String,Object>>(){});
        final Map<String, Map<String, Object>> properties = (Map<String,Map<String, Object>>) vertexData.get(GraphSONModule.TOKEN_PROPERTIES);
        final Object[] propsAsArray = Stream.concat(properties.entrySet().stream().flatMap(e->Stream.of(e.getKey(), e.getValue().get("value"))),
                Stream.of(Element.LABEL, vertexData.get(GraphSONModule.TOKEN_LABEL))).toArray();
        final Vertex v = vertexMaker.apply(vertexData.get(GraphSONModule.TOKEN_ID), propsAsArray);

        if (vertexData.containsKey(GraphSONModule.TOKEN_OUT) && (direction == Direction.BOTH || direction == Direction.OUT))
            readVertexOutEdges(edgeMaker, vertexData);

        if (vertexData.containsKey(GraphSONModule.TOKEN_IN) && (direction == Direction.BOTH || direction == Direction.IN))
            readVertexInEdges(edgeMaker, vertexData);

        return v;
    }

    private static void readVertexInEdges(final QuintFunction<Object, Object, Object, String, Object[], Edge> edgeMaker, final Map<String, Object> vertexData) {
        final List<Map<String,Object>> edgeDatas = (List<Map<String,Object>>) vertexData.get(GraphSONModule.TOKEN_IN);
        for (Map<String,Object> edgeData : edgeDatas) {
            final Map<String, Map<String, Object>> edgeProperties = (Map<String,Map<String, Object>>) edgeData.get(GraphSONModule.TOKEN_PROPERTIES);
            final Object[] edgePropsAsArray = edgeProperties.entrySet().stream().flatMap(e -> Stream.of(e.getKey(), e.getValue().get("value"))).toArray();
            edgeMaker.apply(
                    edgeData.get(GraphSONModule.TOKEN_ID),
                    edgeData.get(GraphSONModule.TOKEN_OUT),
                    edgeData.get(GraphSONModule.TOKEN_IN),
                    edgeData.get(GraphSONModule.TOKEN_LABEL).toString(),
                    edgePropsAsArray);
        }
    }

    private static void readVertexOutEdges(final QuintFunction<Object, Object, Object, String, Object[], Edge> edgeMaker, final Map<String, Object> vertexData) {
        final List<Map<String,Object>> edgeDatas = (List<Map<String,Object>>) vertexData.get(GraphSONModule.TOKEN_OUT);
        for (Map<String,Object> edgeData : edgeDatas) {
            final Map<String, Map<String, Object>> edgeProperties = (Map<String,Map<String, Object>>) edgeData.get(GraphSONModule.TOKEN_PROPERTIES);
            final Object[] edgePropsAsArray = edgeProperties.entrySet().stream().flatMap(e -> Stream.of(e.getKey(), e.getValue().get("value"))).toArray();
            edgeMaker.apply(
                    edgeData.get(GraphSONModule.TOKEN_ID),
                    edgeData.get(GraphSONModule.TOKEN_OUT),
                    edgeData.get(GraphSONModule.TOKEN_IN),
                    edgeData.get(GraphSONModule.TOKEN_LABEL).toString(),
                    edgePropsAsArray);
        }
    }

    @Override
    public Edge readEdge(final InputStream inputStream, final QuintFunction<Object, Object, Object, String, Object[], Edge> edgeMaker) throws IOException {
        final Map<String,Object> edgeData = mapper.readValue(inputStream, new TypeReference<Map<String,Object>>(){});
        final Map<String, Map<String, Object>> properties = (Map<String,Map<String, Object>>) edgeData.get(GraphSONModule.TOKEN_PROPERTIES);
        final Object[] propsAsArray = properties.entrySet().stream().flatMap(e -> Stream.of(e.getKey(), e.getValue().get("value"))).toArray();
        return edgeMaker.apply(
                edgeData.get(GraphSONModule.TOKEN_ID),
                edgeData.get(GraphSONModule.TOKEN_OUT),
                edgeData.get(GraphSONModule.TOKEN_IN),
                edgeData.get(GraphSONModule.TOKEN_LABEL).toString(),
                propsAsArray);
    }

    public static class Builder {
        private final Graph g;
        private ObjectMapper mapper = new GraphSONObjectMapper();

        public Builder(final Graph g) {
            this.g = g;
        }

        public Builder customSerializer(final SimpleModule module) {
            this.mapper = new GraphSONObjectMapper(
                    Optional.ofNullable(module).orElseThrow(IllegalArgumentException::new));
            return this;
        }

        public GraphSONReader build() {
            return new GraphSONReader(g, mapper);
        }
    }
}
