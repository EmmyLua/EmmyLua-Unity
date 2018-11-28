using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Reflection.Emit;
using System.Text;
using System.Threading;
using UnityEditor;
using UnityEditor.Callbacks;
using UnityEngine;

[InitializeOnLoad]
public class EmmyLuaService
{
	private static Socket listener;

	private static Thread reciveThread;

	private static int PORT = 9988;


	static EmmyLuaService()
	{
		BeginListener();
	}
	
	static void BeginListener()
	{
		try
		{
			if (listener == null)
			{
				listener = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
				var ip = IPAddress.Parse("127.0.0.1");
				listener.Bind(new IPEndPoint(ip, PORT));
				listener.Listen(10);
			}
			listener.BeginAccept(OnConnect, listener);
		}
		catch (Exception e)
		{
			Debug.LogException(e);
		}
	}

	private static void OnConnect(IAsyncResult ar)
	{
		if (listener != null)
		{
			var client = listener.EndAccept(ar);
			SendData(client);
		}
		BeginListener();
	}

	[DidReloadScripts]
	static void UpdateScripts()
	{
		BeginListener();
	}

	private static void WriteString(BinaryWriter writer, string value)
	{
		var encoding = Encoding.UTF8;
		var chars = encoding.GetBytes(value);
		writer.Write(chars.Length);
		writer.Write(chars);
	}

	private static void SendData(Socket client)
	{
		
		var buf = new MemoryStream();
		var writer = new BinaryWriter(buf);
		writer.Seek(4, SeekOrigin.Begin);
		var types = GetTypes();
		foreach (var type in types)
		{
			var fullName = type.FullName;
			if (!string.IsNullOrEmpty(fullName))
			{
				// full name
				WriteString(writer, fullName);
				
				// base type full name
				{
					string baseTypeFullName = null;
					if (type.BaseType != null)
						baseTypeFullName = type.BaseType.FullName;
					writer.Write(baseTypeFullName != null);
					if (baseTypeFullName != null)
						WriteString(writer, baseTypeFullName);
				}
				
				// fields
				var fields = type.GetFields(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly);
				writer.Write(fields.Length);
				foreach (var fi in fields)
				{
					WriteString(writer, fi.Name);
					WriteString(writer, fi.FieldType.FullName ?? "any");
				}

				// properties
				var properties = type.GetProperties(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly);
				writer.Write(properties.Length);
				foreach (var pi in properties)
				{
					WriteString(writer, pi.Name);
					WriteString(writer, pi.PropertyType.FullName ?? "any");
				}
				
				// methods
				var methods =
					(from mi in type.GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly)
					where !mi.Name.StartsWith("get_") && !mi.Name.StartsWith("set_")
					select mi).ToArray();
				
				writer.Write(methods.Count());
				foreach (var mi in methods)
				{
					// name
					WriteString(writer, mi.Name);
					
					// parameters
					var parameterInfos = mi.GetParameters();
					writer.Write(parameterInfos.Length);
					foreach (var pi in parameterInfos)
					{
						WriteString(writer, pi.Name);
						WriteString(writer, pi.ParameterType.FullName ?? "any");
					}
					
					// returns
					var ret = mi.ReturnType;
					WriteString(writer, ret.FullName ?? "any");
				}
			}
		}
		writer.Flush();
		// 发送大小
		var len = (int) buf.Length;
		writer.Seek(0, SeekOrigin.Begin);
		writer.Write(len - 4);
		// 发送包体
		client.Send(buf.GetBuffer());
		writer.Close();
	}

	private static Type[] GetTypes()
	{
		var unityTypes = from assembly in AppDomain.CurrentDomain.GetAssemblies()
			where !(assembly.ManifestModule is ModuleBuilder)
			from type in assembly.GetExportedTypes()
			where type.Namespace != null && type.Namespace.StartsWith("UnityEngine") && !isExcluded(type)
			      && type.BaseType != typeof(MulticastDelegate) && !type.IsInterface && !type.IsEnum
			select type;
		var arr =  unityTypes.ToArray();

		return arr;
	}

	private static bool isExcluded(Type type)
	{
		return false;
	}
}
