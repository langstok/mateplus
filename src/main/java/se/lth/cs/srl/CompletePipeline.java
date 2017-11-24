package se.lth.cs.srl;

import is2.data.SentenceData09;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import edu.stanford.nlp.dcoref.CorefChain;
import edu.stanford.nlp.dcoref.CorefChain.CorefMention;
import edu.stanford.nlp.dcoref.CorefCoreAnnotations.CorefChainAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.util.CoreMap;
import se.lth.cs.srl.corpus.Corpus;
import se.lth.cs.srl.corpus.CorpusSentence;
import se.lth.cs.srl.corpus.Predicate;
import se.lth.cs.srl.corpus.Sentence;
import se.lth.cs.srl.corpus.StringInText;
import se.lth.cs.srl.corpus.Word;
import se.lth.cs.srl.io.ANNWriter;
import se.lth.cs.srl.io.CoNLL09Writer;
import se.lth.cs.srl.io.SentenceWriter;
import se.lth.cs.srl.languages.German;
import se.lth.cs.srl.languages.Language;
import se.lth.cs.srl.languages.Language.L;
import se.lth.cs.srl.options.CompletePipelineCMDLineOptions;
import se.lth.cs.srl.options.FullPipelineOptions;
import se.lth.cs.srl.pipeline.Pipeline;
import se.lth.cs.srl.pipeline.Reranker;
import se.lth.cs.srl.pipeline.Step;
import se.lth.cs.srl.preprocessor.HybridPreprocessor;
import se.lth.cs.srl.preprocessor.PipelinedPreprocessor;
import se.lth.cs.srl.preprocessor.Preprocessor;
import se.lth.cs.srl.util.ChineseDesegmenter;
import se.lth.cs.srl.util.ExternalProcesses;
import se.lth.cs.srl.util.FileExistenceVerifier;
import se.lth.cs.srl.util.Util;

public class CompletePipeline {

	private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

	public Preprocessor pp;
	public SemanticRoleLabeler srl;
	
	public static CompletePipeline getCompletePipeline(
			FullPipelineOptions options) throws ZipException, IOException,
			ClassNotFoundException {		
		
		Preprocessor pp = Language.getLanguage().getPreprocessor(options);
		Parse.parseOptions = options.getParseOptions();
		if(options.semaforserver!=null) {
			Parse.parseOptions.skipPD = true;
			Parse.parseOptions.skipPI = true;
		}
		SemanticRoleLabeler srl;
		if (options.reranker) {
			srl = new Reranker(Parse.parseOptions);
		} else {
			ZipFile zipFile = new ZipFile(Parse.parseOptions.modelFile);
			if (Parse.parseOptions.skipPI) {
				srl = Pipeline.fromZipFile(zipFile, new Step[] { Step.pd,
						Step.ai, Step.ac });
			} else {
				srl = Pipeline.fromZipFile(zipFile);
			}
			zipFile.close();
		}
		CompletePipeline pipeline = new CompletePipeline(pp, srl);
		return pipeline;
	}

	private CompletePipeline(Preprocessor preprocessor, SemanticRoleLabeler srl) {
		this.pp = preprocessor;
		this.srl = srl;
	}

	public Sentence parse(String sentence) throws Exception {
		return parseX(Arrays.asList(pp.tokenizeplus(sentence)));
	}

	public Sentence parse(List<String> words) throws Exception {
		Sentence s = new Sentence(pp.preprocess(words.toArray(new String[words
				.size()])), false);
		srl.parseSentence(s);
		return s;
	}

	public Sentence parseX(List<StringInText> words) throws Exception {
		String[] array = new String[words.size()];
		for (int i = 0; i < array.length; i++)
			array[i] = words.get(i).word();
		SentenceData09 tmp = pp.preprocess(array);
		Sentence s = new Sentence(tmp, false);
		for (int i = 0; i < array.length; i++) {
			s.get(i).setBegin(words.get(i).begin());
			s.get(i).setEnd(words.get(i).end());
		}
		srl.parse(s);
		return s;
	}

	public Sentence parseOraclePI(List<String> words, List<Boolean> isPred)
			throws Exception {
		Sentence s = new Sentence(pp.preprocess(words.toArray(new String[words
				.size()])), false);
		for (int i = 0; i < isPred.size(); ++i) {
			if (isPred.get(i)) {
				s.makePredicate(i);
			}
		}
		srl.parseSentence(s);
		return s;
	}

	public static void main(String[] args) throws Exception {
		CompletePipelineCMDLineOptions options = new CompletePipelineCMDLineOptions();
		options.parseCmdLineArgs(args);
		String error = FileExistenceVerifier
				.verifyCompletePipelineAllNecessaryModelFiles(options);
		if (error != null) {
			System.err.println(error);
			System.err.println();
			System.err.println("Aborting.");
			System.exit(1);
		}

		CompletePipeline pipeline = getCompletePipeline(options);
		
		BufferedReader in = new BufferedReader(new InputStreamReader(
				new FileInputStream(options.input), Charset.forName("UTF-8")));
		
		SentenceWriter writer = null;

		if (options.printANN)
			writer = new ANNWriter(options.output);
		else
			writer = new CoNLL09Writer(options.output);

		long start = System.currentTimeMillis();
		int senCount;

		if(options.glovedir!=null) {
			senCount = parseFullDocument(options, pipeline, in, writer);
		}
		else if (options.loadPreprocessorWithTokenizer) {
			senCount = parseNonSegmentedLineByLine(options, pipeline, in,
					writer);
		} else {
			senCount = parseCoNLL09(options, pipeline, in, writer);
		}

		in.close();
		writer.close();

		long time = System.currentTimeMillis() - start;
		System.out.println(pipeline.getStatusString());
		System.out.println();
		System.out.println("Total parsing time (ms):  "
				+ Util.insertCommas(time));
		System.out.println("Overall speed (ms/sen):   "
				+ Util.insertCommas(time / senCount));

	}

	private static int parseFullDocument (
			CompletePipelineCMDLineOptions options, CompletePipeline pipeline,
			BufferedReader in, SentenceWriter writer) throws IOException,
			Exception {
		
		/** initialize **/
		Properties props = new Properties();
		props.put("annotators",
				"tokenize, ssplit, pos, lemma, ner, parse, dcoref");
		props.put("dcoref.sievePasses", "MarkRole," + "DiscourseMatch,"
				+ "ExactStringMatch," + "RelaxedExactStringMatch,"
				+ "PreciseConstructs," + "StrictHeadMatch1,"
				+ "StrictHeadMatch2," + "StrictHeadMatch3,"
				+ "StrictHeadMatch4," + "RelaxedHeadMatch");
		StanfordCoreNLP stanfordpipeline = new StanfordCoreNLP(props);		
		ExternalProcesses glove = new ExternalProcesses(options.glovedir);
		
		/** read full text **/
		int senCount = 0;
		String str;		
		StringBuffer text = new StringBuffer();
		while ((str = in.readLine()) != null) {
			text.append(str);
			text.append("\n");
		}
		
		/** document-level preprocessing **/
		Annotation document = new Annotation(text.toString());
		stanfordpipeline.annotate(document);

		Map<String, Double[]> word2vecs = glove.createvecs(document);
		
		Corpus c = new Corpus("tmp");

		/** sentence-level preprocessing **/
		for (CoreMap sentence : document.get(SentencesAnnotation.class)) {
			StringBuffer posOutput = new StringBuffer();

			for (CoreLabel token : sentence.get(TokensAnnotation.class)) {
				if (posOutput.length() > 0) {
					posOutput.append(" ");
				}
				posOutput.append(token.word());
				posOutput.append("_");
				posOutput.append(token.tag());
			}

			String parse = ExternalProcesses.runProcess("nc " + options.mstserver.replaceAll(":", " "), posOutput.toString());
			parse = parse.replaceAll("-\t-", "_\t_\n@#").replaceAll("@#\t", "")
					.replaceAll("@#", "");

			String[] lines = parse.split("\n");
			String[] words = new String[lines.length + 1];
			String[] lemmas = new String[lines.length + 1];
			String[] tags = new String[lines.length + 1];
			String[] morphs = new String[lines.length + 1];
			int[] heads = new int[lines.length];
			String[] deprels = new String[lines.length];

			for (int i = 1; i < words.length; i++) {
				String[] parts = lines[i - 1].split("\t");
				words[i] = sentence.get(TokensAnnotation.class).get(i - 1)
						.word();
				tags[i] = sentence.get(TokensAnnotation.class).get(i - 1).tag();
				lemmas[i] = sentence.get(TokensAnnotation.class).get(i - 1)
						.lemma();
				morphs[i] = "_";
				heads[i - 1] = Integer.parseInt(parts[6]);
				deprels[i - 1] = parts[7];
			}
			Sentence sen = new Sentence(words, lemmas, tags, morphs);
			sen.setHeadsAndDeprels(heads, deprels);
		
			/* add labeled predicates from SEMAFOR */
			String json = ExternalProcesses.runProcess("nc " + options.semaforserver.replaceAll(":", " "), parse);
			Pattern pred_frame = Pattern
					.compile("\\{\"target\":\\{\"name\":\"([A-Za-z_]*)\",\"spans\":\\[\\{\"start\":([0-9]*),\"");
			Matcher m = pred_frame.matcher(json);
			while (m.find()) {
				String frame = m.group(1);
				int index = Integer.parseInt(m.group(2));
				System.out.println(index + "\t" + frame);

				sen.makePredicate(index + 1);
				((Predicate) sen.get(index + 1)).setSense(frame);
			}
			
			for(Word w : sen)
				if(word2vecs.containsKey(w.getForm().toLowerCase()))
					w.setRep(word2vecs.get(w.getForm().toLowerCase()));

			new CorpusSentence(sen, c);
		}

		/* add coref output to corpus */
		Map<Integer, CorefChain> coref = document
				.get(CorefChainAnnotation.class);
		int num = 1;
		// this can be null apparently?!
		if(coref!=null && coref.entrySet()!=null) {
			for (Map.Entry<Integer, CorefChain> entry : coref.entrySet()) {
				CorefChain cc = entry.getValue();
				// skip singleton mentions
				if (cc.getMentionsInTextualOrder().size() == 1)
					continue;
	
				for (CorefMention m : cc.getMentionsInTextualOrder()) {
					c.addMention(c.get(m.sentNum - 1), m.headIndex, num);
				}
				num++;
			}
		}

		for (Sentence sen : c) {
			pipeline.srl.parseSentence(sen);
			senCount++;
			if (senCount % 100 == 0)
				System.out.println("Processing sentence " + senCount);
			writer.write(sen);
		}
		return senCount;
	}
	
	private static int parseNonSegmentedLineByLine(
			CompletePipelineCMDLineOptions options, CompletePipeline pipeline,
			BufferedReader in, SentenceWriter writer) throws IOException,
			Exception {
		int senCount = 0;
		String str;

		while ((str = in.readLine()) != null) {
			Sentence s = pipeline.parse(str);
			writer.write(s);
			senCount++;
			if (senCount % 100 == 0)
				System.out.println("Processing sentence " + senCount); // TODO,
																		// same
																		// as
																		// below.
		}

		return senCount;
	}

	private static int parseCoNLL09(CompletePipelineCMDLineOptions options,
			CompletePipeline pipeline, BufferedReader in, SentenceWriter writer)
			throws IOException, Exception {
		List<String> forms = new ArrayList<String>();
		forms.add("<root>");
		List<Boolean> isPred = new ArrayList<Boolean>();
		isPred.add(false);
		String str;
		int senCount = 0;

		while ((str = in.readLine()) != null) {
			if (str.trim().equals("")) {
				Sentence s;
				if (options.desegment) {
					s = pipeline.parse(ChineseDesegmenter.desegment(forms
							.toArray(new String[0])));
				} else {
					s = options.skipPI ? pipeline.parseOraclePI(forms, isPred)
							: pipeline.parse(forms);
				}
				forms.clear();
				forms.add("<root>");
				isPred.clear();
				isPred.add(false); // Root is not a predicate
				writer.write(s);
				senCount++;
				if (senCount % 100 == 0) { // TODO fix output in general, don't
											// print to System.out. Wrap a
											// printstream in some (static)
											// class, and allow people to adjust
											// this. While doing this, also add
											// the option to make the output
											// file be -, ie so it prints to
											// stdout. All kinds of errors
											// should goto stderr, and nothing
											// should be printed to stdout by
											// default
					System.out.println("Processing sentence " + senCount);
				}
			} else {
				String[] tokens = WHITESPACE_PATTERN.split(str);
				forms.add(tokens[1]);
				if (options.skipPI)
					isPred.add(tokens[12].equals("Y"));
			}
		}

		if (forms.size() > 1) { // We have the root token too, remember!
			writer.write(pipeline.parse(forms));
			senCount++;
		}
		return senCount;
	}

	public String getStatusString() {
		// StringBuilder ret=new
		// StringBuilder("Semantic role labeling pipeline status\n\n");
		StringBuilder ret = new StringBuilder();
		long allocated = Runtime.getRuntime().totalMemory() / 1024;
		long free = Runtime.getRuntime().freeMemory() / 1024;
		ret.append("Memory usage:\n");
		ret.append("Allocated:\t\t\t" + Util.insertCommas(allocated) + "kb\n");
		ret.append("Used:\t\t\t\t" + Util.insertCommas((allocated - free))
				+ "kb\n");
		ret.append("Free:\t\t\t\t" + Util.insertCommas(free) + "kb\n");
		System.gc();
		long freeWithGC = Runtime.getRuntime().freeMemory() / 1024;
		ret.append("Free (after gc call):\t" + Util.insertCommas(freeWithGC)
				+ "kb\n");
		ret.append("\n");
		// ret.append("Time spent doing tokenization (ms):           "+Util.insertCommas(pp.tokenizeTime)+"\n");
		// ret.append("Time spent doing lemmatization (ms):          "+Util.insertCommas(pp.lemmatizeTime)+"\n");
		// ret.append("Time spent doing pos-tagging (ms):            "+Util.insertCommas(pp.tagTime)+"\n");
		// ret.append("Time spent doing morphological tagging (ms):  "+Util.insertCommas(pp.mtagTime)+"\n");
		// ret.append("Time spent doing dependency parsing (ms):     "+Util.insertCommas(pp.dpTime)+"\n");
		ret.append(pp.getStatus()).append('\n');
		ret.append("Time spent doing semantic role labeling (ms): "
				+ Util.insertCommas(srl.parsingTime) + "\n");
		ret.append("\n\n");
		ret.append(srl.getStatus());
		return ret.toString().trim();
	}
}
