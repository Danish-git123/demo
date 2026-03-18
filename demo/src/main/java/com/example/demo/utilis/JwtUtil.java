package com.example.demo.utilis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECNamedCurveSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;


@Component
public class JwtUtil {

    private PublicKey publicKey;

    @PostConstruct
    public void init() throws Exception {
        // Fetch Supabase JWKS and extract the public key
        String jwksUrl = "https://gpnvnccaubqgwbreqjbd.supabase.co/auth/v1/.well-known/jwks.json";

        RestTemplate restTemplate = new RestTemplate();
        String jwksJson = restTemplate.getForObject(jwksUrl, String.class);

        // Parse the JWKS JSON to extract x and y coordinates
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jwksJson);
        JsonNode key = root.get("keys").get(0);

        String x = key.get("x").asText();
        String y = key.get("y").asText();

        // Build the EC PublicKey from x, y coordinates
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        ECPoint ecPoint = new ECPoint(
                new BigInteger(1, Base64.getUrlDecoder().decode(x)),
                new BigInteger(1, Base64.getUrlDecoder().decode(y))
        );
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("prime256v1");
        ECNamedCurveSpec params = new ECNamedCurveSpec("prime256v1", spec.getCurve(), spec.getG(), spec.getN());
        ECPublicKeySpec pubKeySpec = new ECPublicKeySpec(ecPoint, params);
        this.publicKey = keyFactory.generatePublic(pubKeySpec);
    }

    public Claims validateAndGetClaims(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)   // ← PublicKey not SecretKey
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUserId(String token) {
        return validateAndGetClaims(token).getSubject();
    }

    public String extractEmail(String token) {
        return (String) validateAndGetClaims(token).get("email");
    }

    public boolean isValid(String token) {
        try {
            validateAndGetClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
