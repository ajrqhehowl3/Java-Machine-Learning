package network;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Random;

import layer.Layer;
import optimizer.Optimizer;
import optimizer.SGDOptimizer;
import utils.Loss;
import utils.Tensor;
import utils.UtilMethods;

public class SequentialNN implements NeuralNetwork, SupervisedNeuralNetwork{
	private ArrayList<Layer> layers = new ArrayList<Layer>();
	private int inputSize;
	
	public SequentialNN(int inputSize){
		this.inputSize = inputSize;
	}
	
	@Override
	public int size(){
		return layers.size();
	}

	@Override
	public Layer layer(int idx){
		return layers.get(idx);
	}

	@Override
	public void add(Layer l){
		l.init(layers.isEmpty() ? inputSize : layers.get(layers.size() - 1).nextSize());
		layers.add(l);
	}
	
	@Override
	public void add(Layer l, double[][] weights, double[] bias){
		l.init(layers.isEmpty() ? inputSize : layers.get(layers.size() - 1).nextSize(), weights, bias);
		layers.add(l);
	}

	@Override
	public Tensor[] predict(Tensor[] input){
		Tensor[] res = new Tensor[input.length];
		
		for(int i = 0; i < input.length; i++){
			res[i] = predict(input[i]);
		}
		
		return res;
	}
	
	@Override
	public Tensor predict(Tensor input){
		for(int i = 0; i < layers.size(); i++){
			input = layers.get(i).forwardPropagate(input);
		}
		return input;
	}
	
	@Override
	public Tensor[] predictFull(Tensor input){
		Tensor[] res = new Tensor[layers.size() + 1];
		res[0] = input;
		for(int i = 1; i < layers.size() + 1; i++){
			input = layers.get(i - 1).forwardPropagate(input);
			res[i] = input;
		}
		return res;
	}
	
	@Override
	public int inputSize(){
		return inputSize;
	}
	
	@Override
	public int outputSize(){
		return layers.get(layers.size() - 1).nextSize();
	}
	
	@Override
	public void fit(Tensor[] input, Tensor[] target, boolean verbose, boolean printNet){
		fit(input, target, 1000, 1, false, verbose, printNet);
	}
	
	@Override
	public void fit(Tensor[] input, Tensor[] target, int epochs, int batchSize, boolean shuffle, boolean verbose, boolean printNet){
		fit(input, target, epochs, batchSize, Loss.squared, new SGDOptimizer(), shuffle, verbose, printNet);
	}
	
	@Override
	public void fit(Tensor[] input, Tensor[] target, int epochs, int batchSize, Loss loss, Optimizer optimizer, boolean shuffle, boolean verbose, boolean printNet){
		fit(input, target, epochs, batchSize, loss, optimizer, 0.0, shuffle, verbose, printNet);
	}
	
	@Override
	public void fit(Tensor[] input, Tensor[] target, int epochs, int batchSize, Loss loss, Optimizer optimizer, double regLambda, boolean shuffle, boolean verbose, boolean printNet){
		double weightSum = 0.0;
		int weightCount = 0;
		int[][] weightShapes = new int[layers.size()][0];
		int[][] biasShapes = new int[layers.size()][0];
		for(int i = 0; i < layers.size(); i++){
			weightSum += layers.get(i).weights().reduce(0, (a, b) -> a + regLambda * b);
			weightShapes[i] = layers.get(i).weights().shape();
			weightCount += layers.get(i).weights().size();
			biasShapes[i] = layers.get(i).bias().shape();
		}
		
		optimizer.init(weightShapes, biasShapes);
		
		for(int i = 0; i < epochs; i++){
			double totalLoss = 0.0;
			
			if(verbose && (i == epochs - 1 || (epochs < 10 ? 0 : (i % (epochs / 10))) == 0)){
				System.out.println(UtilMethods.makeStr('=', 30));
				System.out.println("Epoch " + UtilMethods.format(i) + ":");
				System.out.println();
				System.out.println(UtilMethods.makeStr('-', 5) + " Before " + UtilMethods.makeStr('-', 5));
				if(printNet)
					System.out.println(toString());
				System.out.println(UtilMethods.makeStr('-', 18));
				System.out.println();
			}
			
			if(shuffle)
				UtilMethods.shuffle(input, target);
			
			Random r = new Random();
			
			for(int j = 0; j < input.length; j++){
				Tensor[] res = predictFull(input[j]);
				
				// handle dropout and scaling weights
				for(int k = 0; k < layers.size(); k++){
					Layer l = layers.get(k);
					res[k] = res[k].map(w -> {
						if(r.nextDouble() < l.dropout()){
							return 0.0;
						}else{
							return w / (1.0 - l.dropout());
						}
					});
				}
				
				totalLoss += loss.loss(res[res.length - 1], target[j]);
				
				if(verbose && ((i == epochs - 1 || (epochs < 10 ? 0 : (i % (epochs / 10))) == 0) && (input.length < 10 ? 0 : (j % (input.length / 10))) == 0)){
					System.out.print("Input: ");
					System.out.println(input[j]);
					System.out.print("Output: ");
					System.out.println(res[res.length - 1]);
					System.out.print("Target: ");
					System.out.println(target[j]);
					System.out.println();
				}
				
				// add regularization's derivative to the loss function's derivative
				Tensor lossDerivative = loss.derivative(res[res.length - 1], target[j]);
				lossDerivative = lossDerivative.add(weightSum / weightCount);
				
				backPropagate(res, lossDerivative, regLambda, weightCount, optimizer);
				
				if(j + 1 % batchSize == 0 || j == input.length - 1){
					weightSum = 0.0;
					for(int k = 0; k < layers.size(); k++){
						layers.get(k).update();
						weightSum += layers.get(k).weights().reduce(0, (a, b) -> a + regLambda * b);
					}
				}
			}
			if(verbose && (i == epochs - 1 || (epochs < 10 ? 0 : (i % (epochs / 10))) == 0)){
				System.out.println(UtilMethods.makeStr('-', 5) + " After " + UtilMethods.makeStr('-', 6));
				if(printNet)
					System.out.println(toString());
				System.out.println(UtilMethods.makeStr('-', 18));
			}
			if(i == epochs - 1 || (epochs < 10 ? 0 : (i % (epochs / 10))) == 0){
				if(verbose){
					System.out.println();
				}
				System.out.println("Total loss: " + UtilMethods.format(totalLoss / input.length));
			}
			if(verbose && (i == epochs - 1 || (epochs < 10 ? 0 : (i % (epochs / 10))) == 0)){
				System.out.println(UtilMethods.makeStr('=', 30));
			}
		}
	}
	
	@Override
	public void backPropagate(Tensor[] result, Tensor error, double regLambda, int weightCount, Optimizer optimizer){
		for(int i = size() - 1; i >= 0; i--){
			error = layers.get(i).backPropagate(result[i], result[i + 1], error, regLambda, weightCount, optimizer, i);
		}
		optimizer.update();
	}
	
	@Override
	public String toString(){
		final int limit = 1000;
		
		StringBuilder b = new StringBuilder();
		for(int i = 0; i < layers.size(); i++){
			b.append("\nLayer " + (i + 1) + ":\n");
			b.append(UtilMethods.makeStr('-', 10) + "\n");
			b.append("Weights:\n");
			String weights = layers.get(i).weights().toString();
			b.append((weights.length() > limit ? (weights.substring(0, limit) + "...") : weights) + "\n");
			b.append(UtilMethods.makeStr('-', 10) + "\n");
			b.append("Biases:\n");
			String bias = layers.get(i).bias().toString();
			b.append((bias.length() > limit ? (bias.substring(0, limit) + "...") : bias) + "\n");
			b.append(UtilMethods.makeStr('=', 10) + "\n");
		}
		
		return b.toString();
	}
	
	@Override
	public void saveToFile(String path){
		int totalLayerSize = 0;
		for(int i = 0; i < layers.size(); i++){
			totalLayerSize += layers.get(i).byteSize();
		}
		ByteBuffer bb = ByteBuffer.allocate(totalLayerSize);
		for(int i = 0; i < layers.size(); i++){
			bb.put(layers.get(i).bytes());
		}
		bb.flip();
		try{
			Files.write(Paths.get(path), bb.array());
		}catch(Exception e){
			e.printStackTrace();
		}
	}
	
	@Override
	public void loadFromFile(String path){
		byte[] bytes = null;
		try{
			bytes = Files.readAllBytes(Paths.get(path));
		}catch(Exception e){
			e.printStackTrace();
		}
		ByteBuffer bb = ByteBuffer.wrap(bytes);
		for(int i = 0; i < layers.size(); i++){
			layers.get(i).readBytes(bb);
		}
	}
}
