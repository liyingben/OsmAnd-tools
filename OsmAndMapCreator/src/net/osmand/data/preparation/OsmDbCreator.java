package net.osmand.data.preparation;

import gnu.trove.list.array.TLongArrayList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Map.Entry;

import net.osmand.data.City.CityType;
import net.osmand.osm.edit.Entity;
import net.osmand.osm.edit.Node;
import net.osmand.osm.edit.Relation;
import net.osmand.osm.edit.Way;
import net.osmand.osm.edit.Entity.EntityId;
import net.osmand.osm.edit.Entity.EntityType;
import net.osmand.osm.edit.OSMSettings.OSMTagKey;
import net.osmand.osm.io.IOsmStorageFilter;
import net.osmand.osm.io.OsmBaseStorage;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.anvisics.jleveldb.ArraySerializer;
import com.anvisics.jleveldb.ext.DBAccessor;
import com.anvisics.jleveldb.ext.DBWriteBatch;
import com.anvisics.jleveldb.ext.WriteOptions;

public class OsmDbCreator implements IOsmStorageFilter {

	private static final Log log = LogFactory.getLog(OsmDbCreator.class);

	public static final int BATCH_SIZE_OSM = 100000;

	// do not store these tags in the database, just ignore them
	final String[] tagsToIgnore= {"created_by","source","converted_by"};
	
	DBDialect dialect;
	int currentCountNode = 0;
	private PreparedStatement prepNode;
	int allNodes = 0;

	int currentRelationsCount = 0;
	private PreparedStatement prepRelations;
	int allRelations = 0;

	int currentWaysCount = 0;
	private PreparedStatement prepWays;
	int allWays = 0;

	private Connection dbConn;

	private DBAccessor database;
	private DBWriteBatch batch;
	private WriteOptions options;


	public OsmDbCreator() {
	}

	public void initDatabase(DBDialect dialect, Object databaseConn) throws SQLException {
		
		this.dialect = dialect;
		if(dialect == DBDialect.NOSQL){
			database = (DBAccessor) databaseConn;
			batch = new DBWriteBatch();
			options = new WriteOptions();
		} else {
			this.dbConn = (Connection) databaseConn;
			// prepare tables
			Statement stat = dbConn.createStatement();
			dialect.deleteTableIfExists("node", stat);
			stat.executeUpdate("create table node (id bigint primary key, latitude double, longitude double, tags blob)"); //$NON-NLS-1$
			stat.executeUpdate("create index IdIndex ON node (id)"); //$NON-NLS-1$
			dialect.deleteTableIfExists("ways", stat);
			stat.executeUpdate("create table ways (id bigint, node bigint, ord smallint, tags blob, boundary smallint, primary key (id, ord))"); //$NON-NLS-1$
			stat.executeUpdate("create index IdWIndex ON ways (id)"); //$NON-NLS-1$
			dialect.deleteTableIfExists("relations", stat);
			stat.executeUpdate("create table relations (id bigint, member bigint, type smallint, role varchar(1024), ord smallint, tags blob, primary key (id, ord))"); //$NON-NLS-1$
			stat.executeUpdate("create index IdRIndex ON relations (id)"); //$NON-NLS-1$
			stat.close();

			prepNode = dbConn.prepareStatement("insert into node values (?, ?, ?, ?)"); //$NON-NLS-1$
			prepWays = dbConn.prepareStatement("insert into ways values (?, ?, ?, ?, ?)"); //$NON-NLS-1$
			prepRelations = dbConn.prepareStatement("insert into relations values (?, ?, ?, ?, ?, ?)"); //$NON-NLS-1$
			dbConn.setAutoCommit(false);
		}
	}

	public void finishLoading() throws SQLException {
		if (dialect != DBDialect.NOSQL) {
			if (currentCountNode > 0) {
				prepNode.executeBatch();
			}
			prepNode.close();
			if (currentWaysCount > 0) {
				prepWays.executeBatch();
			}
			prepWays.close();
			if (currentRelationsCount > 0) {
				prepRelations.executeBatch();
			}
			prepRelations.close();
		} else {
			database.write(options, batch);
		}
	}
	
	public static String serializeEntityWOId(Entity e){
		StringBuilder builder = new StringBuilder();
		
		ArraySerializer.startArray(builder, true);
		if(!e.getTags().isEmpty()){
			ArraySerializer.startArray(builder, true);
			boolean f = true;
			for(Map.Entry<String, String> es : e.getTags().entrySet()){
				ArraySerializer.value(builder, es.getKey(), f);
				f = false;
				ArraySerializer.value(builder, es.getValue(), f);
			}
			
			ArraySerializer.endArray(builder);
		}
		if (e instanceof Node) {
			ArraySerializer.value(builder, String.valueOf((float) ((Node) e).getLatitude()), false);
			ArraySerializer.value(builder, String.valueOf((float) ((Node) e).getLongitude()), false);
		} else if (e instanceof Way) {
			ArraySerializer.startArray(builder, false);
			boolean f = true;
			TLongArrayList nodeIds = ((Way) e).getNodeIds();
			for (int j = 0; j < nodeIds.size(); j++) {
				ArraySerializer.value(builder, String.valueOf(nodeIds.get(j)), f);
				f = false;
			}
			ArraySerializer.endArray(builder);
		} else {
			
			ArraySerializer.startArray(builder, false);
			boolean f = true;
			for(Entry<EntityId, String> l : ((Relation) e).getMembersMap().entrySet()) {
				String k = l.getKey().getType() == EntityType.NODE ? "0" : (l.getKey().getType() == EntityType.WAY ? "1" : "2");
				ArraySerializer.value(builder, k + l.getKey().getId(), f);
				f = false;
				ArraySerializer.value(builder, l.getValue(), f);
			}
			ArraySerializer.endArray(builder);
			
		}
		
		ArraySerializer.endArray(builder);
		
		return builder.toString();
	}

	@Override
	public boolean acceptEntityToLoad(OsmBaseStorage storage, EntityId entityId, Entity e) {
		// put all nodes into temporary db to get only required nodes after loading all data
		if (dialect == DBDialect.NOSQL) {
			String key;
			currentCountNode++;
			if (e instanceof Node) {
				if (!e.getTags().isEmpty()) {
					allNodes++;
				}
				key = "0" + e.getId();
			} else if (e instanceof Way) {
				allWays++;
				key = "1" + e.getId();
			} else {
				allRelations++;
				key = "2" + e.getId();
			}
			batch.Put(key, serializeEntityWOId(e));
			if (currentCountNode > BATCH_SIZE_OSM) {
				database.write(options, batch);
				batch = new DBWriteBatch();
				long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
				log.info(Runtime.getRuntime().totalMemory() / (1024 * 1024) + " MB Total " + (usedMemory / (1024 * 1024)) + " MB used memory");
				currentCountNode = 0;
			}
		} else {
			try {
				e.removeTags(tagsToIgnore);
				ByteArrayOutputStream tags = new ByteArrayOutputStream();
				try {
					for (Entry<String, String> i : e.getTags().entrySet()) {
						// UTF-8 default
						tags.write(i.getKey().getBytes("UTF-8"));
						tags.write(0);
						tags.write(i.getValue().getBytes("UTF-8"));
						tags.write(0);
					}
				} catch (IOException es) {
					throw new RuntimeException(es);
				}
				if (e instanceof Node) {
					currentCountNode++;
					if (!e.getTags().isEmpty()) {
						allNodes++;
					}
					prepNode.setLong(1, e.getId());
					prepNode.setDouble(2, ((Node) e).getLatitude());
					prepNode.setDouble(3, ((Node) e).getLongitude());
					prepNode.setBytes(4, tags.toByteArray());
					prepNode.addBatch();
					if (currentCountNode >= BATCH_SIZE_OSM) {
						prepNode.executeBatch();
						dbConn.commit(); // clear memory
						currentCountNode = 0;
					}
				} else if (e instanceof Way) {
					allWays++;
					short ord = 0;
					TLongArrayList nodeIds = ((Way) e).getNodeIds();
					boolean city = CityType.valueFromString(((Way)e).getTag(OSMTagKey.PLACE)) != null;
					int boundary = ((Way)e).getTag(OSMTagKey.BOUNDARY) != null || city ? 1 : 0; 
					for (int j = 0; j < nodeIds.size(); j++) {
						currentWaysCount++;
						if (ord == 0) {
							prepWays.setBytes(4, tags.toByteArray());
						}
						prepWays.setLong(1, e.getId());
						prepWays.setLong(2, nodeIds.get(j));
						prepWays.setLong(3, ord++);
						prepWays.setInt(5, boundary);
						prepWays.addBatch();
					}
					if (currentWaysCount >= BATCH_SIZE_OSM) {
						prepWays.executeBatch();
						dbConn.commit(); // clear memory
						currentWaysCount = 0;
					}
				} else {
					allRelations++;
					short ord = 0;
					for (Entry<EntityId, String> i : ((Relation) e).getMembersMap().entrySet()) {
						currentRelationsCount++;
						if (ord == 0) {
							prepRelations.setBytes(6, tags.toByteArray());
						}
						prepRelations.setLong(1, e.getId());
						prepRelations.setLong(2, i.getKey().getId());
						prepRelations.setLong(3, i.getKey().getType().ordinal());
						prepRelations.setString(4, i.getValue());
						prepRelations.setLong(5, ord++);
						prepRelations.addBatch();
					}
					if (currentRelationsCount >= BATCH_SIZE_OSM) {
						prepRelations.executeBatch();
						dbConn.commit(); // clear memory
						currentRelationsCount = 0;
					}
				}
				
			} catch (SQLException ex) {
				log.error("Could not save in db", ex); //$NON-NLS-1$
			}
		}
		// do not add to storage
		return false;
	}

	public int getAllNodes() {
		return allNodes;
	}

	public int getAllRelations() {
		return allRelations;
	}

	public int getAllWays() {
		return allWays;
	}
	

}
