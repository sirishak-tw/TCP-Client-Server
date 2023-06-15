//  TEAM:
//Sirisha Krishna Murthy 01658953
//Toshi Bhat   016583307

import java.io.*;
import java.net.*;
import java.util.*;

public class Server {

	// Port number for tcp connection
	private static final int PORT = 5005;
	// Server Capacity to receive packets
	private static final int SERVER_CAPACITY = 200;// 32768;
	// Wraparound threshold for sequence numbers
	private static final int WRAPAROUND_THRESHOLD = 65536;
	// Goodput calculation threshold
	private static final int GOODPUT_THRESHOLD = 2000;// 2000;
	// Handshake messages
	private static final String SYN_MSG = "SYN";
	private static final String SYN_SUCCESS = "Connection Successful!";
	private static ServerSocket server;

	private static List<Integer> rcvrWindowSizes = new ArrayList<Integer>();
	private static List<Integer> missedPacketsTracker = new ArrayList<Integer>();
	private static List<Integer> rcvdPacketsTracker = new ArrayList<Integer>();

	private static int windowTransmissionEndFlag = -1;
	private static int communicationTransmissionEndFlag = -2;
	private static int retransmissionEndFlag = -3;

	// Main function handles creation of socket and error handling logic
	public static void main(String args[])
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
				// Create server object to invoke contructor
				Server server = new Server();
				connected = true;
			} catch (SocketException e) {
				server.close();
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
				server.close();
				System.out.println("EOF exception");
				try {
					Thread.sleep(1000);
				} catch (InterruptedException ex) {
					System.out.println("Error: Thread interrupted");
					ex.printStackTrace();
				}
			} catch (Exception e) {
				server.close();
				System.out.println("in the generic exception block");
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

	// The Server function does all the packet processing
	public Server() throws Exception, SocketException {
		System.out.println("IP For Server Machine is : " + InetAddress.getLocalHost());

		server = new ServerSocket(PORT);

		// Create a client socket to receive data
		Socket clientSocket = server.accept();
		DataOutputStream dataOutput = new DataOutputStream(clientSocket.getOutputStream());

		// Read input data from client using stream
		DataInputStream dataInput = new DataInputStream(clientSocket.getInputStream());
		String msg = dataInput.readUTF();

		// If handshake is succesfull proceed with data receiving
		if (msg.equals(SYN_MSG)) {

			dataOutput.writeUTF(SYN_SUCCESS);
			System.out.println(SYN_SUCCESS);
			// Call method to handle all packet related processes
			exchangeData(dataInput, dataOutput);
		}
		// Call method to generate output files to write data
		generateOutputFiles();

		// Close all connections
		dataInput.close();
		dataOutput.close();
		server.close();
		clientSocket.close();

	}

	// Method to receive packets
	public static void exchangeData(DataInputStream dataInput, DataOutputStream dataOutput)
			throws Exception, SocketException {
		List<Integer> missingPacketsList = new ArrayList<Integer>();
		Queue<Integer> rcvdPacketsBuffer = new LinkedList<Integer>();
		Random randGen = new Random();
		int missingFlag = 0;
		boolean communicationFlag = true;
		int nextSequence = 1;
		double averageGoodPut = 0;
		int ackCount = 0;
		int windowSize = SERVER_CAPACITY;
		int currentSequence = 0;

		// While the connection is still ongoing
		while (communicationFlag) {
			missingFlag = 0;
			// Transmit windowsize to be the server capacity
			dataOutput.writeInt(windowSize);
			rcvrWindowSizes.add(windowSize);

			// Loop to process single window
			while (true) {
				// Read data sent from the client
				currentSequence = dataInput.readInt();

				// If client sends end of window message , exit loop
				if (currentSequence < 0) {
					break;
				}

				// Store packets in buffer for processing
				rcvdPacketsBuffer.add(currentSequence);
				// Store packets in tracker for wrting output files
				rcvdPacketsTracker.add(currentSequence);

				// Call function to compute next sequence of nu,ber and check if any number is
				// missing
				Map<String, Integer> resData = computeNextSequence(missingPacketsList, currentSequence, nextSequence,
						missedPacketsTracker, missingFlag);
				// update next sequence
				nextSequence = resData.get("nextSequence");
				// update if packet is missing flag
				missingFlag = resData.get("missingFlag");

				// Calculate goodput of every 2000 packets and hold it in average Goodput
				averageGoodPut = calculateGoodPut(missingPacketsList, averageGoodPut);

			}

			// If client is signalling end of communication, send acks for all remaining
			// packets
			if (currentSequence == communicationTransmissionEndFlag) {
				for (int i = rcvdPacketsBuffer.size(); i > 0; i--, ackCount++) {
					dataOutput.writeInt(rcvdPacketsBuffer.poll());
				}
				dataOutput.writeInt(windowTransmissionEndFlag);
				communicationFlag = false;
				break;
			} else {
				// We use the AIMD(Additive Increase Multiplicative Decrease) congestion control
				// protocol to adjust window size

				// Set Window Size to half if packet is lost (Multiplicative Decrease Part)
				if (missingFlag == 1) {
					windowSize /= 2;
					// Additively increase window size as long as no packet dropped as long as we
					// don't exceed server capacity
				} else if (windowSize + 5 < SERVER_CAPACITY) {
					windowSize += 5;
				}

				for (int k = 0; k < rcvdPacketsBuffer.size(); k++) {
					// Send acks to all received packets in the window
					dataOutput.writeInt(rcvdPacketsBuffer.poll());

					ackCount++;
				}
			}

			// When window is done, signal it to client
			dataOutput.writeInt(windowTransmissionEndFlag);

		}

		// When client is retransmitting reeive those packets
		receiveRemainingRetransmitPackets(currentSequence, missingPacketsList, rcvdPacketsBuffer, dataInput);

		// Send acks for retransmit packets
		ackCount = sendAcksForRetransmit(rcvdPacketsBuffer, dataOutput, ackCount);

		// write output to console
		writeOutput(ackCount, rcvdPacketsTracker, averageGoodPut);

	}

	private static int sendAcksForRetransmit(Queue<Integer> rcvdPacketsBuffer, DataOutputStream dataOutput,
			int ackCount) throws IOException {
		// while the received buffer isnt empty, send acks to client
		while (!rcvdPacketsBuffer.isEmpty()) {
			dataOutput.writeInt(rcvdPacketsBuffer.poll());

			ackCount++;
		}
		dataOutput.writeInt(windowTransmissionEndFlag);
		return ackCount;
	}

	private static void writeOutput(int ackCount, List<Integer> rcvdPacketsTracker, double averageGoodPut) {
		System.out.println("\n\n");
		// System.out.println("-----------------------------------------------------------------------------------------------------");
		System.out.println(
				"-----------------------------------------------------------------------------------------------------\n");
		System.out.println("\t Acknowledged Count \t | \t Packets Recieved \t | \t Average goodPut ");
		System.out.println(
				"-----------------------------------------------------------------------------------------------------");
		System.out
				.println("\t\t" + ackCount + "\t\t | \t" + rcvdPacketsTracker.size() + "\t\t\t | \t" + averageGoodPut);
		System.out.println(
				"-----------------------------------------------------------------------------------------------------");
	}

	private static void receiveRemainingRetransmitPackets(int currentSequence, List<Integer> missingPacketsList,
			Queue<Integer> rcvdPacketsBuffer, DataInputStream dataInput) throws IOException {
		while (true) {
			// receive packets from client
			currentSequence = dataInput.readInt();

			if (currentSequence == retransmissionEndFlag)
				break;

			// add retransmitted packets to tracker and Buffer and remover from missing
			// packet list
			rcvdPacketsTracker.add(currentSequence);
			rcvdPacketsBuffer.add(currentSequence);

			missingPacketsList.remove(new Integer(currentSequence));
		}
	}

	private static double calculateGoodPut(List<Integer> missingPacketsList, double averageGoodPut) {
		// Calculate goodput for evry 2000 packets
		if (rcvdPacketsTracker.size() % GOODPUT_THRESHOLD == 0) {
			double goodPut = ((double) rcvdPacketsTracker.size())
					/ (rcvdPacketsTracker.size() + missingPacketsList.size());
			averageGoodPut += (goodPut - averageGoodPut)
					/ (rcvdPacketsTracker.size() / GOODPUT_THRESHOLD);
		}
		return averageGoodPut;
	}

	private static Map<String, Integer> computeNextSequence(List<Integer> missingPacketsList, int currentSequence,
			int nextSequence, List<Integer> missedPacketsTracker, int missingFlag) {
		// Check if the packet is retransmitted
		if (missingPacketsList.contains(currentSequence)) {
			// Remove it from missing packet list
			missingPacketsList.remove(new Integer(currentSequence));

		} else if (currentSequence != nextSequence) {
			// Check if received byte is what is expected consider packets are missing and
			// keep processing till packets are received inorder
			while (nextSequence != currentSequence) {

				missedPacketsTracker.add(nextSequence);
				missingPacketsList.add(nextSequence);

				nextSequence = incrementSequence(nextSequence);
			}
			nextSequence = incrementSequence(currentSequence);
			missingFlag = 1;

		}
		// If expected packet is received, compute next seqence number
		else {
			nextSequence = incrementSequence(currentSequence);
		}

		// return result in a hashmap
		Map<String, Integer> res = new HashMap<>();
		res.put("nextSequence", nextSequence);
		res.put("missingFlag", missingFlag);
		return res;
	}

	// Method to find next sequence
	public static int incrementSequence(int nextSequence) {
		return (nextSequence % WRAPAROUND_THRESHOLD) + 1;
	}

	public static void generateOutputFiles() throws Exception {
		File windowSizesFile = new File(
				"/Users/spartan/Documents/CCS/windowSizes.txt");
		FileWriter w_fw = new FileWriter(windowSizesFile, false);
		File receivedPacketsFile = new File(
				"/Users/spartan/Documents/CCS/receivedPackets.txt");
		FileWriter r_fw = new FileWriter(receivedPacketsFile, false);
		for (int i = 0; i < rcvdPacketsTracker.size(); i++) {
			r_fw.append(String.valueOf(rcvdPacketsTracker.get(i)) + " \n");

		}
		r_fw.close();
		for (int i = 0; i < rcvrWindowSizes.size(); i++) {
			w_fw.append(String.valueOf(rcvrWindowSizes.get(i)) + " \n");

		}
		w_fw.close();

		File missedPacketsFile = new File(
				"/Users/spartan/Documents/CCS/missedPackets.txt");
		FileWriter m_fw = new FileWriter(missedPacketsFile, false);

		for (int i = 0; i < missedPacketsTracker.size(); i++) {
			m_fw.append(String.valueOf(missedPacketsTracker.get(i)) + " \n");

		}
		m_fw.close();

	}
}