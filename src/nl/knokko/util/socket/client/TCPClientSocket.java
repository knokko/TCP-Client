/*******************************************************************************
 * The MIT License
 *
 * Copyright (c) 2018 knokko
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *  
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *******************************************************************************/
package nl.knokko.util.socket.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import nl.knokko.util.bits.BitHelper;
import nl.knokko.util.bits.BitOutput;
import nl.knokko.util.bits.ByteArrayBitInput;
import nl.knokko.util.bits.ByteArrayBitOutput;
import nl.knokko.util.protocol.BitProtocol;

public abstract class TCPClientSocket<State> implements Runnable {

	private String host;
	private int port;

	private Socket socket;
	private OutputStream output;

	private final State state;
	private final BitProtocol<TCPClientSocket<State>> listener;

	private boolean stopping;

	public TCPClientSocket(BitProtocol<TCPClientSocket<State>> listener, State state) {
		this.state = state;
		this.listener = listener;
	}

	public State getState() {
		return state;
	}

	public void start(String host, int port) {
		if (port < 0 || port > Character.MAX_VALUE)
			throw new IllegalArgumentException("Invalid port number: " + port);
		this.host = host;
		this.port = port;
		new Thread(this).start();
	}

	public void close(String reason) {
		System.out.println("Stopping client socket because: " + reason);
		stopping = true;
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException ioex) {
			}
		}
	}

	@Override
	public void run() {
		try {
			socket = new Socket(host, port);
			InputStream input = socket.getInputStream();
			output = socket.getOutputStream();

			byte[] testBytes = new byte[8];
			input.read(testBytes);// this is the server check to see if this is really our address
			output.write(testBytes);

			onConnect();
			int size1 = input.read();
			int size;
			while (size1 != -1) {
				size1++;// no 0 length messages
				if (size1 == 255) {// next 2 bytes determine length
					size = 1 + BitHelper.makeChar((byte) input.read(), (byte) input.read());
				} else if (size1 == 256) {// next 4 bytes determine length
					size = BitHelper.makeInt((byte) input.read(), (byte) input.read(), (byte) input.read(),
							(byte) input.read());
				} else {// length is smaller than 255
					size = size1;
				}
				byte[] inputBytes = new byte[size];
				input.read(inputBytes);
				listener.process(new ByteArrayBitInput(inputBytes), this);
				size1 = input.read();
			}
		} catch (IOException ioex) {
			if (!stopping)
				onError(ioex);
		}
		onClose();
	}

	public boolean isOnline() {
		return socket != null && socket.isConnected() && !socket.isClosed();
	}

	public String getRemoteHost() {
		return host;
	}

	public int getRemotePort() {
		return port;
	}

	public BitOutput createOutput() {
		return new Output();
	}

	protected abstract void onError(IOException ioex);

	protected abstract void onConnect();

	protected abstract void onClose();

	private class Output extends ByteArrayBitOutput {

		@Override
		public void terminate() {
			try {
				byte[] messageBytes = getBytes();
				if (messageBytes.length == 0)
					throw new IllegalStateException("Empty messages are not supported");
				if (messageBytes.length < 255) {
					output.write(messageBytes.length - 1);
				} else if (messageBytes.length <= Character.MAX_VALUE + 1) {
					char c = (char) (messageBytes.length - 1);
					output.write(new byte[] { BitHelper.char0(c), BitHelper.char1(c) });
				} else {
					output.write(new byte[] { BitHelper.int0(messageBytes.length), BitHelper.int1(messageBytes.length),
							BitHelper.int2(messageBytes.length), BitHelper.int3(messageBytes.length) });
				}
				output.write(messageBytes);
			} catch (IOException ioex) {
				onError(ioex);
			}
		}
	}
}