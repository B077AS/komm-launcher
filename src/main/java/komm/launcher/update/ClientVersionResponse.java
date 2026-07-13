package komm.launcher.update;

import lombok.Data;

/**
 * Mirror of the hub's {@code ClientVersionResponse} returned by
 * {@code GET /api/client/latest}.
 */
@Data
public class ClientVersionResponse {
    private String version;
    private String downloadUrl;
}
