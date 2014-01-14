package net.lightbody.bmp.proxy.http;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.HashMap;
import java.util.Map;

/** Primate2 **/
public class ProxyAuthenticator extends Authenticator {

	public static final ProxyAuthenticator INSTANCE = new ProxyAuthenticator();

	static {
		Authenticator.setDefault(INSTANCE);
	}

	private Map<String, PasswordAuthentication> credentials = new HashMap<String, PasswordAuthentication>();

	private ProxyAuthenticator() {
	}

	public void setCredentials(String host, int port, String username, String password) {
		credentials.put(host + port, new PasswordAuthentication(username, password.toCharArray()));
	}

	@Override
	protected PasswordAuthentication getPasswordAuthentication() {
		String address = getRequestingHost() + getRequestingPort();
		return credentials.get(address);
	}
}
