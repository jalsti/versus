package comparison;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.apache.jena.query.QuerySolution;
import org.apache.log4j.Logger;

import tools.SparqlQuerier;
import versus.Versus;

import options.ProgOpts;
import options.ProgOpts.OptKeys;

public class ComparisonTable {

    private static final Logger logger = Logger.getLogger(ComparisonTable.class);

    private ArrayList<String> entities = new ArrayList<String>();
    private HashMap<ComplexProperty, Property> properties = new HashMap<ComplexProperty, Property>();
    private ArrayList<Context> contexts = new ArrayList<Context>();
    private ArrayList<FeatureComparison> featureComparisons = new ArrayList<FeatureComparison>();
    private ArrayList<FeatureComparison> removedFeatureComparisons = new ArrayList<FeatureComparison>();
    private int count;
    private Properties configuration = new Properties();
    private Date startdate;
    private Date enddate;

    public ComparisonTable() {
        try {
            configuration.load(new FileInputStream(Versus.CONFIGURATION));
        } catch (FileNotFoundException e) {
            logger.warn(e);
        } catch (IOException e) {
            logger.error(e, e);
        }
    }

    public ComparisonTable versus(String entity) throws InterruptedException {
        logger.info("adding " + entity + " to versus list");
        String uri = entity;
        if (!uri.startsWith("http")) {
            uri = "http://www.wikidata.org/entity/" + entity;
        }
        entities.add(uri);
        populate(uri);
        //populateInverse(uri);
        return this;
    }

    private void populate(String entity) throws InterruptedException {
        try {
            new SparqlQuerier("select ?p ?o where {<" + entity + "> ?p ?o}", Versus.ENDPOINT) {

                @Override
                public void begin() {
                }

                @Override
                public void end() {
                }

                @Override
                public boolean fact(QuerySolution qs) throws InterruptedException {
                    if (qs != null) {
                        if (qs.get("p").isResource() && qs.get("o").isResource()) {
                            String property = qs.getResource("p").toString();
                            String object = qs.getResource("o").toString();
                            if (!Versus.FILTER || property.startsWith("http://dbpedia.org/ontology/") || property.startsWith("http://www.wikidata.org/prop/direct/")) {
                                addProperty(entity, property, object, false);
                            }
                        }
                        return true;
                    }
                    return false;
                }

            }.execute();
        } catch (InterruptedException e) {
            logger.error(e, e);
            if (ProgOpts.get(ProgOpts.OptKeys.STOP_ON_ERRORS).equals("1")) {
                throw e;
            }
        }
    }

    private void populateInverse(String entity) throws InterruptedException {
        try {
            new SparqlQuerier("select ?p ?o where {?o ?p <" + entity + ">}", Versus.ENDPOINT) {

                @Override
                public void begin() {
                }

                @Override
                public void end() {
                }

                @Override
                public boolean fact(QuerySolution qs) throws InterruptedException {
                    if (qs != null) {
                        if (qs.get("p").isResource() && qs.get("o").isResource()) {
                            String property = qs.getResource("p").toString();
                            String object = qs.getResource("o").toString();
                            if (!Versus.FILTER || property.startsWith("http://dbpedia.org/ontology/") || property.startsWith("http://www.wikidata.org/prop/direct/")) {
                                addProperty(entity, property, object, true);
                            }
                        }
                        return true;
                    }
                    return false;
                }

            }.execute();
        } catch (InterruptedException e) {
            logger.error(e, e);
            if (ProgOpts.get(ProgOpts.OptKeys.STOP_ON_ERRORS).equals("1")) {
                throw e;
            }
        }
    }

    public void generate(double threshold) throws InterruptedException {
        logger.info("================================");
        logger.info("comparing entities: " + String.join(", ", entities));
        startdate = new Date();
        System.out.println(startdate);
        computeContexts();
        computeFeatureComparisons();
        selectFeatureComparisons(threshold);
        enddate = new Date();
        System.out.println(enddate);
    }

    private void selectFeatureComparisons(double threshold) throws InterruptedException {
        Collections.sort(contexts, new Comparator<Context>() {
            @Override
            public int compare(Context o1, Context o2) {
                if (o1.getCount() > o2.getCount()) {
                    return 1;
                } else if (o2.getCount() > o1.getCount()) {
                    return -1;
                } else {
                    return 0;
                }
            }
        });
        for (FeatureComparison fc : featureComparisons) {
            fc.computeProbabilityAdvancedPruning(contexts, threshold);
        }
        Collections.sort(featureComparisons, new Comparator<FeatureComparison>() {

            @Override
            public int compare(FeatureComparison o1, FeatureComparison o2) {
                if (o1.getProbability() > o2.getProbability()) {
                    return -1;
                } else if (o2.getProbability() > o1.getProbability()) {
                    return 1;
                } else {
                    return 0;
                }
            }
        });
        for (int i = 0; i < featureComparisons.size(); i++) {
            if (featureComparisons.get(i).getProbability() < threshold) {
                removedFeatureComparisons.add(featureComparisons.get(i));
                featureComparisons.remove(i);
                i--;
            }
        }
    }

    private void computeContexts() throws InterruptedException {
        for (Entry<ComplexProperty, Property> property : properties.entrySet()) {
            contexts.addAll(property.getValue().getContexts(entities.size()));
        }
        Collections.sort(contexts, new Comparator<Context>() {
            @Override
            public int compare(Context o1, Context o2) {
                if (o1.getCount() > o2.getCount()) {
                    return 1;
                } else if (o2.getCount() > o1.getCount()) {
                    return -1;
                } else {
                    return 0;
                }
            }
        });
        int initialCount = contextualCount(contexts, -1);
        for (int i = 0; i < contexts.size(); i++) {
            int localCount = contextualCount(contexts, i);
            logger.trace("context " + i + ": " + contexts.get(i).getComplexProperty() + " " + contexts.get(i).getObject() + " " + contexts.get(i).getCount());
            if (initialCount == localCount) {
                logger.trace("remove " + i + ": " + contexts.get(i).getComplexProperty() + " " + contexts.get(i).getObject() + " " + contexts.get(i).getCount());
                contexts.remove(i);
                i--;
            }
        }
    }

    private int contextualCount(ArrayList<Context> c, int index) throws InterruptedException {
        String queryStr = "";
        for (int i = 0; i < c.size(); i++) {
            if (i != index) {
                queryStr += (queryStr.equals("") ? "" : " . ") + c.get(i).getFilter();
            }
        }
        count = 0;
        try {
            new SparqlQuerier("select (count(*) as ?c) where {" + queryStr + "}", Versus.ENDPOINT) {

                @Override
                public boolean fact(QuerySolution qs) throws InterruptedException {
                    if (qs != null && qs.get("c") != null) {
                        count = qs.getLiteral("c").getInt();
                        return true;
                    }
                    return false;
                }

                @Override
                public void end() {
                }

                @Override
                public void begin() {
                }
            }.execute();

        } catch (InterruptedException e) {
            logger.error(e, e);
            if (ProgOpts.get(ProgOpts.OptKeys.STOP_ON_ERRORS).equals("1")) {
                throw e;
            }
        }
        return count;
    }

    private void computeFeatureComparisons() {
        for (Entry<ComplexProperty, Property> property : properties.entrySet()) {
            FeatureComparison fc = property.getValue().getFeatureComparisons(entities.size());
            if (fc != null) {
                featureComparisons.add(fc);
            }
        }
    }

    public void addProperty(String entity, String property, String object, boolean inverse) {
        logger.trace("add property for " + entity + ": " + property + " " + object + " " + inverse);
        ComplexProperty cp = new ComplexProperty(property, inverse);
        Property p = properties.get(cp);
        if (p == null) {
            p = new Property(cp);
        }
        p.addObject(object, entity);
        properties.put(cp, p);
    }

    public void show() throws InterruptedException {

        // if output shall go to file, redirect System.out to it
        PrintStream console = System.out;   // store current System.out in case we write to file below
        if (ProgOpts.get(OptKeys.WRITE_FILE).equals("1")) {
            String id;
            String label;
            String hostname = "";
            try {
                hostname = new URL(Versus.ENDPOINT).getHost();
                hostname = hostname.replace(".", "-");
            } catch (MalformedURLException ex) {
                logger.error(ex, ex);
            }
            String outfilename = ProgOpts.get(OptKeys.DIRECTORY) + "/" + hostname + "__";
            for (String entity : entities) {
                id = FeatureComparison.getId(entity);
                label = FeatureComparison.getLabel(entity).replaceAll("[^a-zA-Z0-9_-]+", "-");
                outfilename += id + "--" + label + "__";
            }
            outfilename += ProgOpts.get(OptKeys.THRESHOLD) + "__";
            outfilename += "errors-" + Versus.getErrorNumber() + "__";
            outfilename += Instant.now().getEpochSecond();

            if (ProgOpts.get(OptKeys.MARKDOWN).equals("0")) {
                outfilename += ".txt";
            } else {
                outfilename += ".md";
            }
            try {
                logger.info("will print output to file '" + outfilename + "'");
                PrintStream o = new PrintStream(new File(outfilename));
                System.setOut(o);
            } catch (FileNotFoundException ex) {
                System.setOut(console);
                logger.warn(ex, ex);
                System.exit(2);
            }
        }

        // table header
        line();
        System.out.print("| " + FeatureComparison.right("Features", FeatureComparison.CHAR_NUMBER));
        for (String entity : entities) {
            if (ProgOpts.get(OptKeys.MARKDOWN).equals("1")) {
                // include link for markdown
                System.out.print(" | " + FeatureComparison.left(FeatureComparison.getLabel(entity) + " ([" + FeatureComparison.getId(entity) + "]" + "(" + entity + "))", FeatureComparison.CHAR_NUMBER));
            } else {
                System.out.print(" | " + FeatureComparison.left(FeatureComparison.getLabel(entity) + " (" + FeatureComparison.getId(entity) + ")", FeatureComparison.CHAR_NUMBER));
            }
        }
        if (ProgOpts.get(OptKeys.MARKDOWN).equals("1")) {
            System.out.println(" | CRL");
            for (int i = 0; i <= entities.size(); i++) {
                System.out.print("|---");
            }
            System.out.println("|---"); // final column for crl
        }

        // table entries
        line();
        for (FeatureComparison fc : featureComparisons) {
            fc.show(entities);
            line();
        }

        // additional info
        System.out.println("");
        System.out.println("* Threshold/Gamma: " + ProgOpts.get(OptKeys.THRESHOLD));
        System.out.println("* Duration: "
                + TimeUnit.MILLISECONDS.toSeconds(enddate.getTime() - startdate.getTime()) + " seconds "
                + "(" + startdate + " - " + enddate + ")");
        System.out.println("* #Queries: " + Versus.getQueryNumber());
        System.out.println("* #Errors: " + Versus.getErrorNumber());
        if (Versus.getErrorNumber() > 0) {
            System.out.println("\t* " + String.join("\n\t* ", Versus.error_queries));
        }

        if (ProgOpts.get(OptKeys.WRITE_FILE).equals("1")) {
            // restore System.out in case it had been changed
            System.setOut(console);
        }

    }

    private void line() {
        if (ProgOpts.get(OptKeys.MARKDOWN).equals("1")) {
            return;
        }

        System.out.print("+" + FeatureComparison.line(FeatureComparison.CHAR_NUMBER + 2) + "+");
        for (int i = 0; i < entities.size(); i++) {
            System.out.print(FeatureComparison.line(FeatureComparison.CHAR_NUMBER + 2) + "+");
        }
        System.out.println();
    }

}
