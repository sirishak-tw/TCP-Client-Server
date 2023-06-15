//  TEAM:
//Sirisha Krishna Murthy 01658953
//Toshi Bhat   016583307

import java.io.*;
import java.net.*;
import java.util.*;

public class Client {

	// Port number for connection
	private static final int PORT = 5005;
	private static final int DROP_RATE = 100;
	// Total number of frames to be transmitted to the server
	private static final int TOTAL_NUMBER_OF_FRAMES = 10000000;
	// Limit for sequence limit
	private static final int WRAPAROUND_THRESHOLD = 65536;
	// Limit after which packets should be retransmitted
	private static final int RETRANSMISSION_THRESHOLD = 200;

	// To be updated to server IP used
	private static final String SERVER_IP = "";

	private static int[] window;
	private static int windowSize = 0;
	private static int totalPacketsSent = 0;
	private static int totalRetransmission = 0;
	private static int windowTransmissionEndFlag = -1;
	private static int communicationTransmissionEndFlag = -2;
	private static int retransmissionEndFlag = -3;

	// map to hold retransmission sequence number and no of packets
	private static Map<Integer, List<Double>> retransmissionMap = new HashMap<Integer, List<Double>>();
	private static int table2Size = 5;
	private static int[] retransmissionCount = new int[table2Size];

	// Start message for communication
	private static final String SYN_MSG = "SYN";

	// Setting up the connection
	public static void main(String[] args)
			throws UnknownHostException, IOException, ClassNotFoundException, InterruptedException {
		// Number of attempts for retry
		int numAttempts = 500;
		boolean connected = false;
		// As long as connection isnt made rety till the attempts remain
		// The code handles exceptions and tries every 10 second for connection till the
		// attempts are not 0
		while (!connected && numAttempts > 0) {
			numAttempts--;
			try {
				// Create client object to invoke contructor
				Client client = new Client();
				connected = true;
			} catch (SocketException e) {
				if (e.getMessage().equals("Connection reset")) {
					System.out.println("Error: Connection reset by remote host");

				}
				if (e.getMessage().equals("Connection refused (Connection refused)")) {
					System.out.println("Error: Connection refused by remote host");

				}

				try {
					Thread.sleep(1000);
				} catch (InterruptedException ex) {
					System.out.println("Error: Thread interrupted");
					ex.printStackTrace();
				}

			} catch (EOFException e) {
				System.out.println("EOF exception");
				e.printStackTrace();
				try {
					Thread.sleep(1000);
				} catch (InterruptedException ex) {
					System.out.println("Error: Thread interrupted");
					ex.printStackTrace();
				}
			} catch (Exception e) {
				System.out.println("in the exception block");
				e.printStackTrace();

				try {
					Thread.sleep(1000);
				} catch (InterruptedException ex) {
					System.out.println("Error: Thread interrupted");
					ex.printStackTrace();
				}
			}
		}
	}

	// The Client function does all the packet processing
	public Client() throws Exception, SocketException {
		Socket serverSocket = null;
		DataOutputStream dataOutput = null;
		DataInputStream dataInput;

		// Connect to the server for tranferring data
		// InetAddress serverAddress = InetAddress.getLocalHost();//
		InetAddress serverAddress = InetAddress.getByName(SERVER_IP);

		serverSocket = new Socket(serverAddress.getHostName(), PORT);

		// write to serverSocket using ObjectOutputStream
		dataOutput = new DataOutputStream(serverSocket.getOutputStream());

		// read to serverSocket using ObjectInputStream
		dataInput = new DataInputStream(serverSocket.getInputStream());

		// Send handshake msg to server to establish connection
		System.out.println("IP For Client Machine is : " + InetAddress.getLocalHost());
		System.out.println("IP For Server Machine is : " + serverAddress);
		dataOutput.writeUTF(SYN_MSG);
		String msg = dataInput.readUTF();

		if (msg != null && !msg.isEmpty()) {
			System.out.println(msg);
			exchangeData(dataOutput, dataInput);

		} else {
			System.out.println("Connection was not established");
		}

		System.out.println("TotalPacketsSent : " + totalPacketsSent);

		generateOutputFiles();
		// close resources
		dataOutput.close();
		dataInput.close();
		serverSocket.close();

	}

	public static void exchangeData(DataOutputStream dataOutput, DataInputStream dataInput) throws Exception {
		Queue<Double> packetLossList = new LinkedList<Double>();
		Random randGen = new Random();

		int nextRetransNumber = RETRANSMISSION_THRESHOLD;
		int currentSequence = 0;
		int ackByte = 0;

		// store sequence numbrs to be sent to server
		int[] dataToBeSent = new int[TOTAL_NUMBER_OF_FRAMES];

		for (int sentCount = 0; sentCount < TOTAL_NUMBER_OF_FRAMES; sentCount++) {
			// create an array of data to be sent array
			dataToBeSent[sentCount] = incrementSequence(sentCount);
		}

		// As long as sequence is less than total number of frames , send packets
		while (currentSequence < TOTAL_NUMBER_OF_FRAMES) {
			// Take the window size input from server
			windowSize = dataInput.readInt();

			// Create a new window size array to send packets
			window = new int[windowSize];
			ackByte = 0;
			int i = 0;

			// If current sequence has exceed the retransmission limit, retransmit stored
			// packets
			Map<String, Integer> retransmitRsMap = restransmitAfterThreshold(currentSequence, nextRetransNumber,
					currentSequence, i, dataInput,
					dataOutput, randGen, packetLossList);
			i = retransmitRsMap.get("i");
			nextRetransNumber = retransmitRsMap.get("nextRetransNumber");

			// As long as window size is available and sequence number is available send
			// packets
			while (i < windowSize && currentSequence < TOTAL_NUMBER_OF_FRAMES) {

				// Copy sequences from data array to window
				window[i] = dataToBeSent[currentSequence];
				currentSequence++;

				// For a random 1 percent prpbability, add packets to packet loss array
				if (randGen.nextInt(100) == 0) {
					packetLossList.add((double) window[i]);
					continue;
				}

				// Send the packet via outputstream
				dataOutput.writeInt(window[i]);
				totalPacketsSent++;
				i++;
			}

			writeEndFlags(currentSequence, dataOutput, communicationTransmissionEndFlag, windowTransmissionEndFlag);

			// Receive acks from server till it sends end ack ie -1
			while (ackByte != windowTransmissionEndFlag) {
				ackByte = dataInput.readInt();
			}
		}

		// Transmit all dropped packets at the end
		List<Double> remainingRetransmissionList = new ArrayList<Double>();
		retransmitDroppedPacketsWindowEnd(packetLossList, remainingRetransmissionList, dataOutput);

		// Receive acks from server for all remaining packets
		ackByte = receiveRemainingAcks(ackByte, dataInput);

	}

	private static void writeEndFlags(int currentSequence, DataOutputStream dataOutput,
			int communicationTransmissionEndFlag, int windowTransmissionEndFlag) throws IOException {
		if (currentSequence == TOTAL_NUMBER_OF_FRAMES) {
			// If total frames is reached, signal to the server end of communication
			dataOutput.writeInt(communicationTransmissionEndFlag);
		} else {
			// else signal end of window
			dataOutput.writeInt(windowTransmissionEndFlag);
		}
	}

	private static int receiveRemainingAcks(int ackByte, DataInputStream dataInput) throws IOException {
		ackByte = 0;
		while (ackByte != windowTransmissionEndFlag) {
			ackByte = dataInput.readInt();
		}
		return ackByte;
	}

	private static void retransmitDroppedPacketsWindowEnd(Queue<Double> packetLossList,
			List<Double> remainingRetransmissionList, DataOutputStream dataOutput) throws IOException {
		if (!packetLossList.isEmpty())
			totalRetransmission++;

		// retansmit packets, update remaining retransmit list to keep a tab on packets
		while (!packetLossList.isEmpty()) {
			double packet = packetLossList.poll();
			int index_retransmissionCount = (int) ((packet - (int) packet) * DROP_RATE);
			retransmissionCount[index_retransmissionCount]++;

			dataOutput.writeInt((int) packet);
			remainingRetransmissionList.add(packet);
			totalPacketsSent++;
		}

		// Put the retransmission count and number of packets transmitted to the map
		retransmissionMap.put(totalRetransmission, remainingRetransmissionList);
		dataOutput.writeInt(retransmissionEndFlag);
	}

	private static Map<String, Integer> restransmitAfterThreshold(int currentSequence, int nextRetransNumber,
			int currentSequence1, int i, DataInputStream dataInput, DataOutputStream dataOutput, Random randGen,
			Queue<Double> packetLossList) throws IOException {
		if (currentSequence > nextRetransNumber) {

			i = resendLostPackets(dataOutput, dataInput, packetLossList, randGen, i);
			// update next retransmission number
			nextRetransNumber = currentSequence + RETRANSMISSION_THRESHOLD;
		}
		Map<String, Integer> res = new HashMap<>();
		res.put("i", i);
		res.put("nextRetransNumber", nextRetransNumber);
		return res;
	}

	// Method is used to resend lost packets
	private static int resendLostPackets(DataOutputStream dataOutput, DataInputStream dataInput,
			Queue<Double> packetLossList, Random randGen, int seqNo) throws IOException {

		List<Double> resendPacketList = new ArrayList<Double>();

		totalRetransmission++;

		while (seqNo < windowSize && !packetLossList.isEmpty()) {
			double packet = packetLossList.poll();

			// Randomly pick 1 percent packets to drop
			if (randGen.nextInt(DROP_RATE) == 0) {
				// Tag each drop by adding drop rate in decimal value
				packet = packet + 1.0 / DROP_RATE;

				packetLossList.add(packet);
				continue;
			}
			window[seqNo] = (int) packet;
			dataOutput.writeInt(window[seqNo]);
			resendPacketList.add(packet);

			totalPacketsSent++;
			// increment the #of retransmission count
			int index_retransmissionCount = (int) ((packet - (int) packet) * DROP_RATE);
			retransmissionCount[index_retransmissionCount]++;

			seqNo++;
		}
		retransmissionMap.put(totalRetransmission, resendPacketList);
		return seqNo;
	}

	// Method to incrmeent sequence number
	public static int incrementSequence(int nextSequence) {
		return (nextSequence % WRAPAROUND_THRESHOLD) + 1;
	}

	// Method to generate required output files
	public static void generateOutputFiles() throws Exception {
		File table = new File("/Users/spartan/Documents/CCS/Table.txt");
		FileWriter fw = new FileWriter(table, false);
		for (Integer i : retransmissionMap.keySet()) {
			// Retransmission Number(i) | number of retransmissions
			fw.append(i + " | " + retransmissionMap.get(i).size() + "\n");

		}
		fw.close();

		File table2 = new File("/Users/spartan/Documents/CCS/Table2_numberofretries.txt");
		FileWriter fw2 = new FileWriter(table2, false);
		for (int i = 0; i < table2Size; i++) {
			// Retransmission Number(i) | number of retransmissions
			fw2.append((i + 1) + " | " + retransmissionCount[i] + "\n");

		}
		fw2.close();
	}
}